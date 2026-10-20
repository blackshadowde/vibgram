package org.telegram.messenger.vibgram.bot

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileStreamLoadOperation
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.messenger.secretmedia.ExtendedDefaultDataSourceFactory
import java.io.File

/**
 * Bridges videos received in bot chats to ExoPlayer (media3).
 *
 * Two ways to play:
 *  1. Stream right away: [mediaItem] + a player built with [buildPlayer] (or [mediaSourceFactory]).
 *     The URI is tg://…, served by the engine's FileStreamLoadOperation, which downloads the byte
 *     ranges ExoPlayer asks for (seeking works) and caches them. Once the file is fully downloaded
 *     the same call returns a plain file:// URI instead.
 *  2. Download first: collect [download] and play [DownloadState.Completed.localPath].
 *
 * A tg:// URI only plays through the engine's data source ([dataSourceFactory]). A plain
 * DefaultDataSource cannot open it — reuse this factory in the existing MediaItem pipeline.
 */
@OptIn(UnstableApi::class)
class BotVideoPlayback @JvmOverloads constructor(
    val account: Int = UserConfig.selectedAccount,
) {
    private val fileLoader: FileLoader get() = FileLoader.getInstance(account)

    // ---- local file ----------------------------------------------------------------------------

    /** The finished local file, if the video has already been downloaded (here or anywhere in the app). */
    fun localFile(message: BotMessage): File? {
        val doc = message.video?.document ?: return null
        return listOf(fileLoader.getPathToAttach(doc, false), fileLoader.getPathToAttach(doc, true))
            .firstOrNull { it != null && it.exists() && it.length() > 0 }
    }

    // ---- download ------------------------------------------------------------------------------

    /**
     * Downloads the message's video with [priority] (FileLoader.PRIORITY_HIGH by default) and
     * reports progress. Completes with [DownloadState.Completed] or [DownloadState.Failed].
     *
     * The download belongs to the engine, not to the collector: cancelling collection only stops
     * listening (it keeps going in the background and shows in Telegram's downloads list / the
     * Movies "Downloaded" row) unless [cancelOnStop] is true. Use [cancelDownload] to stop it.
     */
    @JvmOverloads
    fun download(
        message: BotMessage,
        priority: Int = FileLoader.PRIORITY_HIGH,
        cancelOnStop: Boolean = false,
    ): Flow<DownloadState> = callbackFlow {
        val messageObject = message.source
        val doc = message.video?.document
        if (messageObject == null || doc == null) {
            trySend(DownloadState.Failed(BotError.Download("message has no video")))
            close()
            awaitClose { }
            return@callbackFlow
        }

        localFile(message)?.let {
            trySend(DownloadState.Completed(it.absolutePath))
            close()
            awaitClose { }
            return@callbackFlow
        }

        val attachName = FileLoader.getAttachFileName(doc)
        val center = NotificationCenter.getInstance(account)
        var finished = false
        val observer = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                if (args.isEmpty() || args[0] != attachName || finished) return
                when (id) {
                    NotificationCenter.fileLoadProgressChanged -> {
                        val loaded = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
                        val total = (args.getOrNull(2) as? Number)?.toLong()?.takeIf { it > 0 } ?: doc.size
                        trySend(DownloadState.Downloading(loaded, total))
                    }
                    NotificationCenter.fileLoaded -> {
                        finished = true
                        val file = args.getOrNull(1) as? File ?: localFile(message)
                        trySend(
                            if (file != null) DownloadState.Completed(file.absolutePath)
                            else DownloadState.Failed(BotError.Download("file missing after download"))
                        )
                        close()
                    }
                    NotificationCenter.fileLoadFailed -> {
                        finished = true
                        val reason = if ((args.getOrNull(1) as? Number)?.toInt() == 1) "cancelled" else "download failed"
                        trySend(DownloadState.Failed(BotError.Download(reason)))
                        close()
                    }
                }
            }
        }
        val ids = intArrayOf(NotificationCenter.fileLoadProgressChanged, NotificationCenter.fileLoaded, NotificationCenter.fileLoadFailed)
        ids.forEach { center.addObserver(observer, it) }

        trySend(DownloadState.Downloading(0L, doc.size))
        messageObject.putInDownloadsStore = true
        fileLoader.loadFile(doc, messageObject, priority, 0)

        awaitClose {
            val cleanup = Runnable {
                ids.forEach { center.removeObserver(observer, it) }
                if (cancelOnStop && !finished) fileLoader.cancelLoadFile(doc)
            }
            if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else AndroidUtilities.runOnUIThread(cleanup)
        }
    }
        .buffer(Channel.UNLIMITED)
        .flowOn(Dispatchers.Main.immediate)

    /** Stops a running download of this message's video and removes the partial file. */
    fun cancelDownload(message: BotMessage) {
        val doc = message.video?.document ?: return
        AndroidUtilities.runOnUIThread { fileLoader.cancelLoadFile(doc, true) }
    }

    // ---- ExoPlayer -----------------------------------------------------------------------------

    /**
     * file:// when the video is on disk, otherwise a streamable tg:// URI (needs [dataSourceFactory]).
     * Null when the message has no video.
     */
    fun playableUri(message: BotMessage): Uri? {
        val doc = message.video?.document ?: return null
        localFile(message)?.let { return Uri.fromFile(it) }
        return FileStreamLoadOperation.prepareUri(account, doc, message.source)
    }

    /** Ready-to-play MediaItem for the existing pipeline. Null when the message has no video. */
    fun mediaItem(message: BotMessage): MediaItem? {
        val video = message.video ?: return null
        val uri = playableUri(message) ?: return null
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId("vibgram:${message.chatId}:${message.id}:${video.documentId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(video.fileName.ifEmpty { message.text.lineSequence().firstOrNull().orEmpty() })
                    .build()
            )
        // Only pass real video MIME types; let the extractor sniff octet-stream uploads (mkv/webm…).
        if (video.mimeType.startsWith("video/", ignoreCase = true)) builder.setMimeType(video.mimeType)
        return builder.build()
    }

    /** Data source that understands tg:// (engine streaming) plus file/http/content URIs. */
    fun dataSourceFactory(context: Context): DataSource.Factory =
        ExtendedDefaultDataSourceFactory(context.applicationContext, USER_AGENT)

    fun mediaSourceFactory(context: Context): MediaSource.Factory =
        DefaultMediaSourceFactory(dataSourceFactory(context))

    /** An ExoPlayer wired for tg:// streaming. The caller owns it and must release() it. */
    fun buildPlayer(context: Context): ExoPlayer =
        ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(mediaSourceFactory(context))
            .build()

    /** Location for the sharp thumbnail (ImageReceiver / BackupImageView.setImage). */
    fun thumbnailLocation(message: BotMessage): ImageLocation? {
        val video = message.video ?: return null
        val size = video.thumbnail?.photoSize ?: return null
        return ImageLocation.getForDocument(size, video.document)
    }

    companion object {
        // Same UA the in-app VideoPlayer uses for its data source.
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:10.0) Gecko/20150101 Firefox/47.0 (Chrome)"
    }
}
