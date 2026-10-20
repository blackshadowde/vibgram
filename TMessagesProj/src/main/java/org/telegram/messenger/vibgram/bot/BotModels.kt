package org.telegram.messenger.vibgram.bot

import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC

/*
 * Clean domain model for Vibgram's general-purpose bot pipeline.
 *
 * "chatId" everywhere in this package is the engine's dialog id:
 *   user / bot  -> positive user id
 *   group       -> negative chat id
 *   channel     -> negative channel id (-100… style ids are NOT used here)
 * It is the value MessagesController, SendMessagesHelper and NotificationCenter all key on.
 */

/** A resolved public username. */
data class BotChat(
    val chatId: Long,
    val username: String,
    val title: String,
    val isBot: Boolean,
    val isChannel: Boolean,
)

/** One message in the target chat, already parsed. Immutable snapshot. */
data class BotMessage(
    val id: Int,
    val chatId: Long,
    /** Unix seconds. */
    val date: Int,
    /** Unix seconds; 0 when never edited. Bots edit messages to swap keyboards / pages. */
    val editDate: Int,
    val isOutgoing: Boolean,
    /** Text body, or the media caption. Empty when there is neither. */
    val text: String,
    /** Inline keyboard under the message (rows of buttons). Empty when none. */
    val inlineKeyboard: List<List<BotButton>>,
    /** Custom reply keyboard (replaces the system keyboard). Null when the message has none. */
    val replyKeyboard: ReplyKeyboard?,
    /** True when the bot asked to remove a previously shown reply keyboard. */
    val hidesReplyKeyboard: Boolean,
    val video: BotVideo?,
    /** True while an outgoing message is still on its way to the server (temporary id). */
    val isSending: Boolean,
    val sendFailed: Boolean,
) {
    /**
     * Engine object the message was parsed from. Needed for callback queries, downloads and
     * streaming (it carries the file reference). Not part of equality.
     */
    @Transient
    internal var source: MessageObject? = null
        private set

    internal fun withSource(messageObject: MessageObject): BotMessage = apply { source = messageObject }

    override fun equals(other: Any?): Boolean = other is BotMessage &&
        other.id == id && other.chatId == chatId && other.date == date && other.editDate == editDate &&
        other.isOutgoing == isOutgoing && other.text == text && other.inlineKeyboard == inlineKeyboard &&
        other.replyKeyboard == replyKeyboard && other.hidesReplyKeyboard == hidesReplyKeyboard &&
        other.video == video && other.isSending == isSending && other.sendFailed == sendFailed

    override fun hashCode(): Int = 31 * (31 * id + chatId.hashCode()) + editDate
}

data class ReplyKeyboard(
    val rows: List<List<ReplyButton>>,
    val resize: Boolean,
    val singleUse: Boolean,
    val persistent: Boolean,
    val placeholder: String?,
)

/** A reply-keyboard key. Pressing a plain key sends its text as a normal message. */
data class ReplyButton(val text: String, val kind: Kind) {
    enum class Kind { TEXT, REQUEST_PHONE, REQUEST_LOCATION, REQUEST_POLL, REQUEST_PEER, WEB_APP, OTHER }
}

/** Inline keyboard button. [row]/[column] locate it in the keyboard for stable UI keys. */
sealed class BotButton {
    abstract val text: String
    abstract val row: Int
    abstract val column: Int

    /** Sends a callback query to the bot (the usual "menu" button). */
    data class Callback(
        override val text: String, override val row: Int, override val column: Int,
        val data: ByteArray, val requiresPassword: Boolean,
    ) : BotButton() {
        override fun equals(other: Any?) = other is Callback && other.text == text && other.row == row &&
            other.column == column && other.data.contentEquals(data) && other.requiresPassword == requiresPassword
        override fun hashCode() = (text.hashCode() * 31 + row) * 31 + column
    }

    data class Url(override val text: String, override val row: Int, override val column: Int, val url: String) : BotButton()
    data class LoginUrl(override val text: String, override val row: Int, override val column: Int, val url: String) : BotButton()
    data class WebApp(override val text: String, override val row: Int, override val column: Int, val url: String) : BotButton()
    data class SwitchInline(
        override val text: String, override val row: Int, override val column: Int,
        val query: String, val samePeer: Boolean,
    ) : BotButton()
    data class Copy(override val text: String, override val row: Int, override val column: Int, val copyText: String) : BotButton()
    data class UserProfile(override val text: String, override val row: Int, override val column: Int, val userId: Long) : BotButton()
    data class Game(override val text: String, override val row: Int, override val column: Int) : BotButton()
    data class Buy(override val text: String, override val row: Int, override val column: Int) : BotButton()
    data class Disabled(override val text: String, override val row: Int, override val column: Int) : BotButton()
    data class Unsupported(override val text: String, override val row: Int, override val column: Int) : BotButton()
}

/** Video attached to a message, either as a video message or as a document with a video MIME type. */
data class BotVideo(
    val documentId: Long,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationSeconds: Double,
    val width: Int,
    val height: Int,
    /** The sender marked it streamable (moov atom first); streaming still usually works without it. */
    val supportsStreaming: Boolean,
    /** Sent as a file (MessageMediaDocument without the video attribute) rather than as a video. */
    val isDocument: Boolean,
    val thumbnail: BotThumbnail?,
) {
    /** The engine document, for downloads and stream URIs. Not part of equality. */
    @Transient
    internal var document: TLRPC.Document? = null
        private set

    internal fun withDocument(doc: TLRPC.Document): BotVideo = apply { document = doc }
}

/**
 * Thumbnail reference.
 *
 * [inlineBytes] is the tiny preview embedded in the message, usable instantly with no download:
 *  - [isStripped] = true  -> decode with ImageLoader.getStrippedPhotoBitmap(inlineBytes, "b")
 *  - [isStripped] = false -> a complete JPEG, decode with BitmapFactory
 * For the sharp thumbnail use BotVideoPlayback.thumbnailLocation(video) with an ImageReceiver /
 * BackupImageView, which downloads and caches it.
 */
data class BotThumbnail(
    val width: Int,
    val height: Int,
    val sizeBytes: Int,
    val type: String,
    val inlineBytes: ByteArray?,
    val isStripped: Boolean,
) {
    @Transient
    internal var photoSize: TLRPC.PhotoSize? = null
        private set

    internal fun withPhotoSize(size: TLRPC.PhotoSize): BotThumbnail = apply { photoSize = size }

    override fun equals(other: Any?) = other is BotThumbnail && other.width == width && other.height == height &&
        other.sizeBytes == sizeBytes && other.type == type
    override fun hashCode() = (width * 31 + height) * 31 + type.hashCode()
}

/** What the bot answered to a callback button. All fields optional; bots often answer with nothing. */
data class BotCallbackAnswer(
    val message: String?,
    /** Show [message] as a dialog instead of a toast. */
    val alert: Boolean,
    /** The bot wants the client to open this URL (game / t.me deep link). */
    val url: String?,
)

/** Changes coming from the live listener for one chat. */
sealed class BotUpdate {
    data class NewMessages(val messages: List<BotMessage>) : BotUpdate()
    data class EditedMessages(val messages: List<BotMessage>) : BotUpdate()
    data class DeletedMessages(val ids: List<Int>) : BotUpdate()
    /** An outgoing message got its permanent server id. */
    data class MessageIdChanged(val oldId: Int, val newMessage: BotMessage) : BotUpdate()
    data class SendFailed(val messageId: Int) : BotUpdate()
}

/** Progress of a video download. */
sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : DownloadState() {
        val fraction: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }
    /** [localPath] is a finished, fully written file — pass it to MediaItem.fromUri(Uri.fromFile(...)). */
    data class Completed(val localPath: String) : DownloadState()
    data class Failed(val error: BotError) : DownloadState()
}
