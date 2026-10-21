package org.telegram.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileStreamLoadOperation
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.VibeConfig
import org.telegram.messenger.VibeLayout
import org.telegram.messenger.VibeMovies
import org.telegram.messenger.secretmedia.ExtendedDefaultDataSourceFactory
import org.telegram.messenger.vibgram.bot.BotVideoPlayback
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.EdgeToEdgeSupportMode
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.VibeLoadingTitleView
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vibgram's movie player, Stremio style, on ExoPlayer (media3).
 *
 * - Plays any video message, including videos sent as files (.mp4, .mkv, .mov…), streaming them
 *   through the engine's tg:// loader while they download; plays the local file once downloaded.
 * - Loading screen: the title's backdrop with its logo breathing in the middle (from the Movies
 *   catalog when the file name matches a title; otherwise the video's own thumbnail and name).
 * - Controls: back + title, Rotate Screen, ±10 s, play/pause, seek bar, Subtitles (and audio
 *   tracks), Playback Speed, Video scaling, Player (open in another app).
 * - Falls back to the built-in FFmpeg decoders by itself when the phone can't decode a track.
 * - Remembers the position for Continue Watching.
 */
@OptIn(UnstableApi::class)
class VibePlayerActivity(
    private val message: MessageObject,
    private var meta: VibeMovies.Meta?,
) : BaseFragment(), Player.Listener {

    private var player: ExoPlayer? = null
    private var usingFfmpeg = VibeConfig.preferFfmpeg()
    private var firstFrameShown = false
    private var resumeChecked = false
    private var landscape = true
    private var savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    /** Fill by default (no black bars); the last choice is remembered. */
    private var scaling = org.telegram.messenger.MessagesController.getGlobalMainSettings().getInt(PREF_SCALE, SCALE_FILL)
    private var scrubbing = false
    private var controlsVisible = true

    // views
    private lateinit var root: FrameLayout
    private lateinit var videoFrame: VideoFrame
    private lateinit var textureView: TextureView
    private lateinit var subtitleText: TextView
    private lateinit var loadingLayer: FrameLayout
    private lateinit var backdrop: BackupImageView
    private lateinit var logo: BackupImageView
    private lateinit var logoTitle: TextView
    private lateinit var controls: FrameLayout
    private lateinit var controlsContent: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var playPause: ImageView
    private lateinit var rewind: ImageView
    private lateinit var forward: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var timeLeft: TextView
    private lateinit var timeRight: TextView
    private lateinit var menuLayer: FrameLayout
    private lateinit var menuList: LinearLayout
    private lateinit var errorLayer: LinearLayout
    private lateinit var errorText: TextView
    private var pulse: ValueAnimator? = null

    private val hideControls = Runnable { setControlsVisible(false) }
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            AndroidUtilities.runOnUIThread(this, 500)
        }
    }
    private val showBufferingLogo = Runnable { setLogoPulsing(true, withBackdrop = false) }

    private val document get() = message.document

    private val title: String
        get() = meta?.name?.takeIf { it.isNotBlank() }
            ?: VibeLoadingTitleView.titleOf(message).ifEmpty { FileLoader.getDocumentFileName(document).orEmpty() }
                .ifEmpty { "Video" }

    // ------------------------------------------------------------------ lifecycle

    override fun onFragmentCreate(): Boolean {
        if (document == null) return false
        if (meta == null) {
            VibeMovies.matchTitle(VibeLoadingTitleView.titleOf(message)) { found ->
                if (found != null && meta == null) {
                    meta = found
                    if (fragmentView != null) applyMeta()
                }
            }
        }
        meta?.poster?.let { VibeMovies.rememberPoster(message, it) }
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(ticker)
        AndroidUtilities.cancelRunOnUIThread(hideControls)
        AndroidUtilities.cancelRunOnUIThread(showBufferingLogo)
        stopPulse()
        player?.let { p ->
            val duration = p.duration
            if (duration > 0 && duration != C.TIME_UNSET) {
                VibeMovies.saveProgress(message, title, p.currentPosition, duration)
            }
            p.removeListener(this)
            p.release()
        }
        player = null
        super.onFragmentDestroy()
    }

    override fun onResume() {
        super.onResume()
        val activity = parentActivity ?: return
        savedOrientation = activity.requestedOrientation
        applyOrientation()
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        AndroidUtilities.runOnUIThread(ticker)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        AndroidUtilities.cancelRunOnUIThread(ticker)
        val activity = parentActivity ?: return
        activity.requestedOrientation = savedOrientation
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    override fun isSwipeBackEnabled(event: MotionEvent?): Boolean = false

    override fun isLightStatusBar(): Boolean = false

    // Full screen edge to edge: without this the app's container pads the screen by the
    // (hidden) navigation bar and camera cutout, leaving grey strips around the video.
    override fun getEdgeToEdgeSupportMode(): EdgeToEdgeSupportMode = EdgeToEdgeSupportMode.FULL

    override fun onInsetsInternal(view: View, windowInsets: WindowInsetsCompat): WindowInsetsCompat {
        val cut = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
        applyCutout(cut.left, cut.top, cut.right, cut.bottom)
        return WindowInsetsCompat.CONSUMED
    }

    /**
     * Buttons and menus stay clear of the camera cutout. The video fills the whole screen when
     * filling/stretching; when fitting it keeps the same margin on both sides so it stays centered.
     */
    private fun applyCutout(left: Int, top: Int, right: Int, bottom: Int) {
        if (!::menuLayer.isInitialized || !::errorLayer.isInitialized) return
        controlsContent.setPadding(left, top, right, bottom)
        menuLayer.setPadding(left, top, right, bottom)
        errorLayer.setPadding(left, top, right, bottom)
        videoFrame.sideInset = max(left, right)
        videoFrame.requestLayout()
    }

    override fun onBackPressed(invoked: Boolean): Boolean {
        if (::menuLayer.isInitialized && menuLayer.visibility == View.VISIBLE) {
            if (invoked) hideMenu()
            return false
        }
        return super.onBackPressed(invoked)
    }

    // ------------------------------------------------------------------ view

    @SuppressLint("ClickableViewAccessibility")
    override fun createView(context: Context): View {
        actionBar.setAddToContainer(false)

        root = FrameLayout(context)
        root.setBackgroundColor(0xFF000000.toInt())
        root.keepScreenOn = true
        fragmentView = root

        videoFrame = VideoFrame(context)
        textureView = TextureView(context)
        videoFrame.addView(textureView)
        root.addView(videoFrame, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        subtitleText = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = VibeLayout.boldFont()
            gravity = Gravity.CENTER
            setShadowLayer(dp(4f).toFloat(), 0f, dp(1f).toFloat(), 0xEE000000.toInt())
            setPadding(dp(48f), 0, dp(48f), 0)
        }
        root.addView(subtitleText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.BOTTOM, 0f, 0f, 0f, 36f))

        root.addView(createLoadingLayer(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        root.addView(createControls(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        root.addView(createErrorLayer(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        root.addView(createMenuLayer(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControlsVisible(!controlsVisible)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                seekBy(if (e.x < root.width / 2f) -SKIP_MS else SKIP_MS)
                return true
            }
        })
        // Pinch out = fill the screen, pinch in = show the whole picture (like Netflix / YouTube).
        var pinchHandled = false
        val pinch = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean {
                pinchHandled = false
                return true
            }
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                if (pinchHandled) return true
                val factor = detector.scaleFactor
                if (factor > 1.06f && scaling != SCALE_FILL) {
                    setScaling(SCALE_FILL)
                    flashLabel("Fill screen")
                    pinchHandled = true
                } else if (factor < 0.94f && scaling != SCALE_FIT) {
                    setScaling(SCALE_FIT)
                    flashLabel("Fit to screen")
                    pinchHandled = true
                }
                return pinchHandled
            }
        })
        root.setOnTouchListener { _, e ->
            pinch.onTouchEvent(e)
            if (!pinch.isInProgress && e.pointerCount == 1) gestures.onTouchEvent(e)
            true
        }

        scaleLabel = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            typeface = VibeLayout.boldFont()
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(0xB3000000.toInt())
            }
            alpha = 0f
        }
        root.addView(scaleLabel, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0f, 28f, 0f, 0f))
        videoFrame.mode = scaling

        applyMeta()
        startPlayer(resumeAt = C.TIME_UNSET)
        setControlsVisible(true)
        androidx.core.view.ViewCompat.requestApplyInsets(root)
        return root
    }

    private fun createLoadingLayer(context: Context): View {
        loadingLayer = FrameLayout(context)
        backdrop = BackupImageView(context)
        loadingLayer.addView(backdrop, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        loadingLayer.addView(View(context).apply { setBackgroundColor(0x66000000) }, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        logo = BackupImageView(context)
        logo.imageReceiver.setAspectFit(true)
        loadingLayer.addView(logo, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        logoTitle = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34f)
            typeface = VibeLayout.boldFont()
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(dp(6f).toFloat(), 0f, dp(1f).toFloat(), 0x99000000.toInt())
            setPadding(dp(40f), 0, dp(40f), 0)
        }
        loadingLayer.addView(logoTitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER))

        // The logo sits in the middle, about a quarter of the screen tall, like Stremio.
        loadingLayer.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            val lw = (min(w, h * 2) * 0.42f).toInt()
            val lh = (h * 0.24f).toInt()
            val lp = logo.layoutParams as FrameLayout.LayoutParams
            if (lp.width != lw || lp.height != lh) {
                lp.width = lw
                lp.height = lh
                lp.gravity = Gravity.CENTER
                v.post { logo.requestLayout() }
            }
        }
        setLogoPulsing(true, withBackdrop = true)
        return loadingLayer
    }

    private fun applyMeta() {
        val m = meta
        titleView.text = title
        if (m != null && !m.background.isNullOrEmpty()) {
            backdrop.setImage(m.background, null, null as android.graphics.drawable.Drawable?)
        } else {
            // No catalog match: blurred video thumbnail behind the name.
            val doc = document
            val thumb = doc?.thumbs?.let { FileLoader.getClosestPhotoSizeWithSize(it, 640) }
            if (thumb != null) backdrop.setImage(ImageLocation.getForDocument(thumb, doc), "b", null as android.graphics.drawable.Drawable?, message)
        }
        if (m != null && !m.logo.isNullOrEmpty()) {
            logo.setImage(m.logo, null, null as android.graphics.drawable.Drawable?)
            logo.visibility = View.VISIBLE
            logoTitle.visibility = View.GONE
        } else {
            logo.visibility = View.GONE
            logoTitle.visibility = View.VISIBLE
            logoTitle.text = title
        }
    }

    private fun createControls(context: Context): View {
        controls = FrameLayout(context)
        controls.addView(View(context).apply { setBackgroundColor(0x33000000) }, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        controls.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3000000.toInt(), 0x00000000))
        }, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT.toFloat(), 120f, Gravity.TOP))
        controls.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT.toFloat(), 170f, Gravity.BOTTOM))
        controlsContent = FrameLayout(context)
        controls.addView(controlsContent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        // Top: back + title, Rotate Screen.
        val top = LinearLayout(context)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(icon(context, R.drawable.vibe_player_back, 11).apply {
            contentDescription = "Back"
            setOnClickListener { finishFragment() }
        }, LayoutHelper.createLinear(48, 48))
        titleView = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19f)
            typeface = VibeLayout.boldFont()
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        top.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 12, 0))
        top.addView(action(context, R.drawable.vibe_player_rotate, "Rotate Screen") { toggleRotation() },
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 48))
        controlsContent.addView(top, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56f, Gravity.TOP, 24f, 18f, 32f, 0f))

        // Middle: rewind, play/pause, forward.
        rewind = icon(context, R.drawable.vibe_player_rewind, 19).apply {
            contentDescription = "Back 10 seconds"
            setOnClickListener { seekBy(-SKIP_MS) }
        }
        forward = icon(context, R.drawable.vibe_player_forward, 19).apply {
            contentDescription = "Forward 10 seconds"
            setOnClickListener { seekBy(SKIP_MS) }
        }
        playPause = icon(context, R.drawable.vibe_player_pause, 20).apply {
            contentDescription = "Play or pause"
            setOnClickListener { togglePlay() }
            visibility = View.INVISIBLE
        }
        controlsContent.addView(rewind, LayoutHelper.createFrame(72f, 72f, Gravity.CENTER_VERTICAL or Gravity.LEFT, 120f, 0f, 0f, 0f))
        controlsContent.addView(playPause, LayoutHelper.createFrame(84f, 84f, Gravity.CENTER))
        controlsContent.addView(forward, LayoutHelper.createFrame(72f, 72f, Gravity.CENTER_VERTICAL or Gravity.RIGHT, 0f, 0f, 120f, 0f))

        // Bottom: times + seek bar, then the four actions.
        val bottom = LinearLayout(context)
        bottom.orientation = LinearLayout.VERTICAL
        val seekRow = LinearLayout(context)
        seekRow.orientation = LinearLayout.HORIZONTAL
        seekRow.gravity = Gravity.CENTER_VERTICAL
        timeLeft = timeLabel(context)
        timeRight = timeLabel(context)
        seekBar = SeekBar(context)
        seekRow.addView(timeLeft, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        seekRow.addView(seekBar, LayoutHelper.createLinear(0, 40, 1f, 16, 0, 16, 0))
        seekRow.addView(timeRight, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        bottom.addView(seekRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER
        actions.addView(action(context, R.drawable.vibe_player_subtitles, "Subtitles") { showTracksMenu() }, actionParams())
        actions.addView(action(context, R.drawable.vibe_player_speed, "Playback Speed") { showSpeedMenu() }, actionParams())
        actions.addView(action(context, R.drawable.vibe_player_scaling, "Video scaling") { showScalingMenu() }, actionParams())
        actions.addView(action(context, R.drawable.vibe_player_external, "Player") { showPlayerMenu() }, actionParams())
        bottom.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0f, 6f, 0f, 0f))
        controlsContent.addView(bottom, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.BOTTOM, 32f, 0f, 32f, 14f))

        seekBar.listener = object : SeekBar.Listener {
            override fun onScrubStart() {
                scrubbing = true
                AndroidUtilities.cancelRunOnUIThread(hideControls)
            }
            override fun onScrub(fraction: Float) {
                val d = durationMs()
                if (d > 0) timeLeft.text = formatTime((d * fraction).toLong())
            }
            override fun onScrubEnd(fraction: Float) {
                scrubbing = false
                val d = durationMs()
                if (d > 0) player?.seekTo((d * fraction).toLong())
                scheduleHide()
            }
        }
        timeLeft.text = formatTime(0)
        timeRight.text = formatTime(0)
        return controls
    }

    private fun actionParams() = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 48, 20f, 0f, 20f, 0f)

    private fun createErrorLayer(context: Context): View {
        errorLayer = LinearLayout(context)
        errorLayer.orientation = LinearLayout.VERTICAL
        errorLayer.gravity = Gravity.CENTER
        errorLayer.setBackgroundColor(0xCC000000.toInt())
        errorLayer.visibility = View.GONE
        errorLayer.isClickable = true
        errorText = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
            typeface = VibeLayout.boldFont()
            gravity = Gravity.CENTER
            setPadding(dp(40f), 0, dp(40f), 0)
        }
        errorLayer.addView(errorText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        val buttons = LinearLayout(context)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.addView(pill(context, "Try again", filled = true) {
            errorLayer.visibility = View.GONE
            startPlayer(resumeAt = player?.currentPosition ?: C.TIME_UNSET)
        }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, 6f, 0f, 6f, 0f))
        buttons.addView(pill(context, "Open in another app", filled = false) { openExternally() },
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, 6f, 0f, 6f, 0f))
        buttons.addView(pill(context, "Close", filled = false) { finishFragment() },
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, 6f, 0f, 6f, 0f))
        errorLayer.addView(buttons, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 0))
        return errorLayer
    }

    // ------------------------------------------------------------------ player

    private fun startPlayer(resumeAt: Long) {
        val context = parentActivity ?: return
        val doc = document ?: return
        player?.let {
            it.removeListener(this)
            it.release()
        }
        firstFrameShown = false
        playPause.visibility = View.INVISIBLE
        loadingLayer.visibility = View.VISIBLE
        loadingLayer.alpha = 1f
        backdrop.visibility = View.VISIBLE
        setLogoPulsing(true, withBackdrop = true)

        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                if (usingFfmpeg) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
            .build()
        val dataSource = ExtendedDefaultDataSourceFactory(context.applicationContext, USER_AGENT)
        val p = ExoPlayer.Builder(context.applicationContext)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        p.setVideoTextureView(textureView)
        p.addListener(this)
        player = p

        val uri = playableUri() ?: run {
            showError("This video can't be opened.")
            return
        }
        FileStreamLoadOperation.setPriorityForDocument(doc, FileLoader.PRIORITY_HIGH)
        p.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId("${message.dialogId}_${message.id}").build())
        if (resumeAt != C.TIME_UNSET && resumeAt > 0) {
            p.seekTo(resumeAt)
            resumeChecked = true
        }
        p.prepare()
        p.playWhenReady = true
    }

    /** Local file when downloaded, otherwise a tg:// stream that downloads while it plays. */
    private fun playableUri(): Uri? {
        localFile()?.let { return Uri.fromFile(it) }
        return BotVideoPlayback.streamUri(currentAccount, document ?: return null, message)
    }

    private fun localFile(): File? {
        val doc = document ?: return null
        val loader = FileLoader.getInstance(currentAccount)
        val attach = message.messageOwner?.attachPath?.takeIf { it.isNotEmpty() }?.let(::File)
        return listOfNotNull(attach, loader.getPathToMessage(message.messageOwner), loader.getPathToAttach(doc, false), loader.getPathToAttach(doc, true))
            .firstOrNull { it.exists() && (doc.size <= 0 || it.length() >= doc.size) }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val p = player ?: return
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                if (firstFrameShown) AndroidUtilities.runOnUIThread(showBufferingLogo, 700)
            }
            Player.STATE_READY -> {
                AndroidUtilities.cancelRunOnUIThread(showBufferingLogo)
                if (firstFrameShown) setLogoPulsing(false, withBackdrop = false)
                if (!resumeChecked) {
                    resumeChecked = true
                    val d = p.duration
                    val saved = VibeMovies.watching().firstOrNull {
                        it.account == currentAccount && it.dialogId == message.dialogId && it.messageId == message.id
                    }
                    if (saved != null && d > 0 && saved.progress in 0.01f..0.95f) {
                        p.seekTo((d * saved.progress).toLong())
                    }
                }
            }
            Player.STATE_ENDED -> {
                setControlsVisible(true)
            }
        }
        updateProgress()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        playPause.setImageResource(if (isPlaying) R.drawable.vibe_player_pause else R.drawable.vibe_player_play)
        if (isPlaying) scheduleHide() else AndroidUtilities.cancelRunOnUIThread(hideControls)
    }

    override fun onRenderedFirstFrame() {
        if (firstFrameShown) return
        firstFrameShown = true
        playPause.visibility = View.VISIBLE
        stopPulse()
        loadingLayer.animate().alpha(0f).setDuration(350).withEndAction {
            loadingLayer.visibility = View.GONE
            loadingLayer.alpha = 1f
            backdrop.visibility = View.GONE
        }.start()
        scheduleHide()
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.width > 0 && videoSize.height > 0) {
            videoFrame.videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            videoFrame.requestLayout()
        }
    }

    override fun onCues(cueGroup: CueGroup) {
        subtitleText.text = cueGroup.cues.mapNotNull { it.text }.joinToString("\n")
    }

    override fun onTracksChanged(tracks: Tracks) {
        // The phone can't decode any audio track (AC3/DTS/TrueHD on many phones): switch to the
        // built-in decoders once, keeping the position.
        if (usingFfmpeg) return
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isNotEmpty() && audio.none { g -> (0 until g.length).any { g.isTrackSupported(it) } }) {
            switchToFfmpeg()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val decoderProblem = error.errorCode in DECODER_ERRORS
        if (decoderProblem && !usingFfmpeg) {
            switchToFfmpeg()
            return
        }
        stopPulse()
        showError(
            when {
                decoderProblem -> "This phone can't decode this video. Try opening it in another app."
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                    "This video format isn't supported here. Try opening it in another app."
                error.errorCode in NETWORK_ERRORS -> "Connection lost. Check your internet and try again."
                else -> "Couldn't play this video (${error.errorCodeName})."
            }
        )
    }

    private fun switchToFfmpeg() {
        usingFfmpeg = true
        val position = player?.currentPosition ?: 0L
        AndroidUtilities.runOnUIThread { startPlayer(resumeAt = position) }
    }

    private fun showError(text: String) {
        errorText.text = text
        errorLayer.visibility = View.VISIBLE
        loadingLayer.visibility = View.GONE
        setControlsVisible(false)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.playWhenReady = !p.playWhenReady
        setControlsVisible(true)
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val d = durationMs()
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0).let { if (d > 0) min(it, d) else it }
        p.seekTo(target)
        setControlsVisible(true)
        updateProgress()
    }

    private fun durationMs(): Long = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    private fun updateProgress() {
        if (!::seekBar.isInitialized) return
        val p = player ?: return
        val d = durationMs()
        if (!scrubbing) {
            timeLeft.text = formatTime(p.currentPosition)
            seekBar.progress = if (d > 0) p.currentPosition.toFloat() / d else 0f
        }
        seekBar.buffered = if (d > 0) p.bufferedPosition.toFloat() / d else 0f
        timeRight.text = formatTime(d)
        seekBar.invalidate()
    }

    // ------------------------------------------------------------------ controls visibility

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        controls.animate().cancel()
        if (visible) {
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(180).start()
            scheduleHide()
        } else {
            AndroidUtilities.cancelRunOnUIThread(hideControls)
            controls.animate().alpha(0f).setDuration(220).withEndAction {
                if (!controlsVisible) controls.visibility = View.GONE
            }.start()
        }
    }

    private fun scheduleHide() {
        AndroidUtilities.cancelRunOnUIThread(hideControls)
        // Keep controls up while loading, paused, or in a menu.
        if (player?.isPlaying == true && firstFrameShown && !scrubbing && menuLayer.visibility != View.VISIBLE) {
            AndroidUtilities.runOnUIThread(hideControls, 3_500)
        }
    }

    // ------------------------------------------------------------------ loading logo

    private fun setLogoPulsing(on: Boolean, withBackdrop: Boolean) {
        if (!::loadingLayer.isInitialized) return
        if (on) {
            loadingLayer.animate().cancel()
            loadingLayer.alpha = 1f
            loadingLayer.visibility = View.VISIBLE
            backdrop.visibility = if (withBackdrop) View.VISIBLE else View.GONE
            startPulse()
        } else {
            stopPulse()
            if (firstFrameShown) {
                loadingLayer.animate().alpha(0f).setDuration(300).withEndAction {
                    loadingLayer.visibility = View.GONE
                    loadingLayer.alpha = 1f
                }.start()
            }
        }
    }

    private fun startPulse() {
        if (pulse != null) return
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                // Breathe: translucent → opaque → translucent.
                val wave = (0.5 - 0.5 * Math.cos(t * Math.PI * 2)).toFloat()
                val alpha = 0.25f + 0.75f * wave
                logo.alpha = alpha
                logoTitle.alpha = alpha
            }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        if (::logo.isInitialized) {
            logo.alpha = 1f
            logoTitle.alpha = 1f
        }
    }

    // ------------------------------------------------------------------ actions

    private fun toggleRotation() {
        landscape = !landscape
        applyOrientation()
        setControlsVisible(true)
    }

    private fun applyOrientation() {
        parentActivity?.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun openExternally() {
        val activity = parentActivity ?: return
        val doc = document ?: return
        val file = localFile()
        if (file != null) {
            player?.pause()
            AndroidUtilities.openForView(file, FileLoader.getDocumentFileName(doc), VibeConfig.videoMime(doc), activity, null, false)
        } else {
            message.putInDownloadsStore = true
            FileLoader.getInstance(currentAccount).loadFile(doc, message, FileLoader.PRIORITY_HIGH, 0)
            bulletin("Downloading. Tap Player → Open in another app when it's done.")
        }
    }

    // ------------------------------------------------------------------ menus

    private sealed class MenuRow {
        class Header(val text: String) : MenuRow()
        class Item(val text: String, val checked: Boolean, val enabled: Boolean = true, val onClick: () -> Unit) : MenuRow()
        class Note(val text: String) : MenuRow()
    }

    private fun showTracksMenu() {
        val p = player ?: return
        val rows = ArrayList<MenuRow>()
        val groups = p.currentTracks.groups

        val audio = trackOptions(groups, C.TRACK_TYPE_AUDIO)
        if (audio.size > 1) {
            rows += MenuRow.Header("Audio")
            audio.forEach { (g, i, label) ->
                rows += MenuRow.Item(label, g.isTrackSelected(i), g.isTrackSupported(i)) { selectTrack(g, i) }
            }
        }

        rows += MenuRow.Header("Subtitles")
        val text = trackOptions(groups, C.TRACK_TYPE_TEXT)
        val textOff = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
            text.none { (g, i, _) -> g.isTrackSelected(i) }
        rows += MenuRow.Item("Off", textOff) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            subtitleText.text = ""
        }
        text.forEach { (g, i, label) ->
            rows += MenuRow.Item(label, !textOff && g.isTrackSelected(i), g.isTrackSupported(i)) { selectTrack(g, i) }
        }
        if (text.isEmpty()) rows += MenuRow.Note("This video has no subtitles.")
        showMenu(rows)
    }

    private fun trackOptions(groups: List<Tracks.Group>, type: Int): List<Triple<Tracks.Group, Int, String>> {
        val out = ArrayList<Triple<Tracks.Group, Int, String>>()
        var n = 1
        groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) out += Triple(g, i, trackLabel(g.getTrackFormat(i), n++, type))
        }
        return out
    }

    private fun trackLabel(f: Format, n: Int, type: Int): String {
        val parts = ArrayList<String>()
        f.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
            val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
            if (parts.none { it.contains(name, ignoreCase = true) }) parts += name
        }
        if (type == C.TRACK_TYPE_AUDIO) {
            when (f.sampleMimeType) {
                "audio/ac3" -> parts += "Dolby Digital"
                "audio/eac3", "audio/eac3-joc" -> parts += "Dolby Digital+"
                "audio/true-hd" -> parts += "TrueHD"
                "audio/vnd.dts", "audio/vnd.dts.hd" -> parts += "DTS"
            }
            when (f.channelCount) {
                6 -> parts += "5.1"
                8 -> parts += "7.1"
            }
        }
        return if (parts.isEmpty()) "Track $n" else parts.joinToString(" · ")
    }

    private fun selectTrack(group: Tracks.Group, index: Int) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    private fun showSpeedMenu() {
        val p = player ?: return
        val current = p.playbackParameters.speed
        showMenu(listOf(MenuRow.Header("Playback Speed")) + SPEEDS.map { s ->
            val label = if (s == 1f) "Normal" else (if (s % 1f == 0f) "${s.toInt()}×" else "${s}×")
            MenuRow.Item(label, abs(current - s) < 0.01f) { p.setPlaybackSpeed(s) }
        })
    }

    private fun showScalingMenu() {
        showMenu(listOf(
            MenuRow.Header("Video scaling"),
            MenuRow.Item("Fit to screen", scaling == SCALE_FIT) { setScaling(SCALE_FIT) },
            MenuRow.Item("Fill screen (crop)", scaling == SCALE_FILL) { setScaling(SCALE_FILL) },
            MenuRow.Item("Stretch", scaling == SCALE_STRETCH) { setScaling(SCALE_STRETCH) },
        ))
    }

    private fun setScaling(mode: Int) {
        scaling = mode
        videoFrame.mode = mode
        videoFrame.requestLayout()
        org.telegram.messenger.MessagesController.getGlobalMainSettings().edit().putInt(PREF_SCALE, mode).apply()
    }

    private var scaleLabel: TextView? = null

    private fun flashLabel(text: String) {
        val label = scaleLabel ?: return
        label.text = text
        label.animate().cancel()
        label.alpha = 1f
        label.animate().alpha(0f).setStartDelay(900).setDuration(300).start()
    }

    private fun showPlayerMenu() {
        val downloaded = localFile() != null
        val rows = arrayListOf<MenuRow>(
            MenuRow.Header("Player"),
            MenuRow.Item("Built-in player", true) {},
            MenuRow.Item(if (downloaded) "Open in another app" else "Download, then open in another app", false) { openExternally() },
        )
        rows += MenuRow.Item("Built-in decoders (FFmpeg)", usingFfmpeg) {
            usingFfmpeg = !usingFfmpeg
            startPlayer(resumeAt = player?.currentPosition ?: C.TIME_UNSET)
        }
        rows += MenuRow.Note("Turn on built-in decoders if there's no sound or picture.")
        showMenu(rows)
    }

    private fun createMenuLayer(context: Context): View {
        menuLayer = FrameLayout(context)
        menuLayer.setBackgroundColor(0x66000000)
        menuLayer.visibility = View.GONE
        menuLayer.setOnClickListener { hideMenu() }

        val scroll = ScrollView(context)
        scroll.isVerticalScrollBarEnabled = false
        scroll.background = GradientDrawable().apply {
            cornerRadius = dp(18f).toFloat()
            setColor(0xF21C1B20.toInt())
        }
        scroll.isClickable = true
        menuList = LinearLayout(context)
        menuList.orientation = LinearLayout.VERTICAL
        menuList.setPadding(0, dp(8f), 0, dp(8f))
        scroll.addView(menuList)
        menuLayer.addView(scroll, LayoutHelper.createFrame(340f, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.RIGHT or Gravity.CENTER_VERTICAL, 0f, 24f, 24f, 24f))
        return menuLayer
    }

    private fun showMenu(rows: List<MenuRow>) {
        val context = menuList.context
        menuList.removeAllViews()
        rows.forEach { row ->
            when (row) {
                is MenuRow.Header -> menuList.addView(TextView(context).apply {
                    text = row.text
                    setTextColor(0x99FFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                    typeface = VibeLayout.boldFont()
                    setPadding(dp(20f), dp(12f), dp(20f), dp(6f))
                }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
                is MenuRow.Note -> menuList.addView(TextView(context).apply {
                    text = row.text
                    setTextColor(0x80FFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                    setPadding(dp(20f), dp(6f), dp(20f), dp(10f))
                }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
                is MenuRow.Item -> {
                    val line = LinearLayout(context)
                    line.orientation = LinearLayout.HORIZONTAL
                    line.gravity = Gravity.CENTER_VERTICAL
                    line.setPadding(dp(20f), 0, dp(16f), 0)
                    line.background = org.telegram.ui.ActionBar.Theme.createSelectorDrawable(0x22FFFFFF, org.telegram.ui.ActionBar.Theme.RIPPLE_MASK_ROUNDRECT_6DP)
                    line.alpha = if (row.enabled) 1f else 0.4f
                    line.addView(TextView(context).apply {
                        text = row.text
                        setTextColor(if (row.checked) PURPLE_LIGHT else 0xFFFFFFFF.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                        typeface = if (row.checked) VibeLayout.boldFont() else VibeLayout.regular()
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    }, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
                    if (row.checked) {
                        line.addView(ImageView(context).apply {
                            setImageResource(R.drawable.vibe_ic_done)
                            colorFilter = PorterDuffColorFilter(PURPLE_LIGHT, PorterDuff.Mode.SRC_IN)
                        }, LayoutHelper.createLinear(24, 24))
                    }
                    if (row.enabled) {
                        line.setOnClickListener {
                            row.onClick()
                            hideMenu()
                        }
                    }
                    menuList.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))
                }
            }
        }
        menuLayer.visibility = View.VISIBLE
        AndroidUtilities.cancelRunOnUIThread(hideControls)
    }

    private fun hideMenu() {
        menuLayer.visibility = View.GONE
        scheduleHide()
    }

    // ------------------------------------------------------------------ small views

    /** Round touch target; [padDp] sets how much smaller the glyph is than the button. */
    private fun icon(context: Context, res: Int, padDp: Int) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(res)
        colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
        val pad = dp(padDp.toFloat())
        setPadding(pad, pad, pad, pad)
        background = org.telegram.ui.ActionBar.Theme.createSelectorDrawable(0x33FFFFFF, org.telegram.ui.ActionBar.Theme.RIPPLE_MASK_CIRCLE_TO_BOUND_EDGE)
    }

    private fun action(context: Context, res: Int, label: String, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6f), 0, dp(6f), 0)
        background = org.telegram.ui.ActionBar.Theme.createSelectorDrawable(0x22FFFFFF, org.telegram.ui.ActionBar.Theme.RIPPLE_MASK_ROUNDRECT_6DP)
        addView(ImageView(context).apply {
            setImageResource(res)
            colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
        }, LayoutHelper.createLinear(24, 24))
        addView(TextView(context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            typeface = VibeLayout.regular()
            isSingleLine = true
        }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 12f, 0f, 0f, 0f))
        contentDescription = label
        setOnClickListener { onClick() }
    }

    private fun pill(context: Context, label: String, filled: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        typeface = VibeLayout.boldFont()
        setPadding(dp(18f), 0, dp(18f), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(22f).toFloat()
            if (filled) setColor(PURPLE) else {
                setColor(0x22FFFFFF)
                setStroke(dp(1f), 0x55FFFFFF)
            }
        }
        setOnClickListener { onClick() }
    }

    private fun timeLabel(context: Context) = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        typeface = VibeLayout.regular()
        minWidth = dp(52f)
        gravity = Gravity.CENTER
    }

    private fun bulletin(text: String) {
        if (parentActivity == null) return
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, text).show()
    }

    private fun formatTime(ms: Long): String {
        val s = max(0L, ms / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    // ------------------------------------------------------------------ video frame (scaling)

    /** Lays the video out as Fit, Fill (crop) or Stretch. */
    private class VideoFrame(context: Context) : FrameLayout(context) {
        var videoAspect = 0f
        var mode = SCALE_FIT
        /** Cutout width kept free on both sides in Fit mode. */
        var sideInset = 0

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val inset = if (mode == SCALE_FIT) sideInset else 0
            val viewW = r - l - 2 * inset
            val viewH = b - t
            var w = viewW
            var h = viewH
            if (videoAspect > 0f && mode != SCALE_STRETCH && viewH > 0) {
                val viewAspect = viewW.toFloat() / viewH
                val fitWidth = (videoAspect > viewAspect) == (mode == SCALE_FIT)
                if (fitWidth) {
                    w = viewW
                    h = (viewW / videoAspect).roundToInt()
                } else {
                    h = viewH
                    w = (viewH * videoAspect).roundToInt()
                }
            }
            val left = inset + (viewW - w) / 2
            val top = (viewH - h) / 2
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                child.layout(left, top, left + w, top + h)
            }
        }
    }

    // ------------------------------------------------------------------ seek bar

    /** Thin track, buffered part, purple progress and thumb, like the reference player. */
    private class SeekBar(context: Context) : View(context) {
        interface Listener {
            fun onScrubStart()
            fun onScrub(fraction: Float)
            fun onScrubEnd(fraction: Float)
        }

        var listener: Listener? = null
        var progress = 0f
        var buffered = 0f
        private var dragging = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = dp(9f).toFloat()
            val left = r
            val right = width - r
            val cy = height / 2f
            val th = dp(3f).toFloat()
            val span = max(1f, right - left)
            paint.color = 0x40FFFFFF
            rect.set(left, cy - th / 2, right, cy + th / 2)
            canvas.drawRoundRect(rect, th, th, paint)
            paint.color = 0x66FFFFFF
            rect.set(left, cy - th / 2, left + span * buffered.coerceIn(0f, 1f), cy + th / 2)
            canvas.drawRoundRect(rect, th, th, paint)
            val x = left + span * progress.coerceIn(0f, 1f)
            paint.color = PURPLE
            rect.set(left, cy - th / 2, x, cy + th / 2)
            canvas.drawRoundRect(rect, th, th, paint)
            canvas.drawCircle(x, cy, if (dragging) r * 1.25f else r, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val r = dp(9f).toFloat()
            val f = ((event.x - r) / max(1f, width - 2 * r)).coerceIn(0f, 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    dragging = true
                    progress = f
                    listener?.onScrubStart()
                    listener?.onScrub(f)
                }
                MotionEvent.ACTION_MOVE -> {
                    progress = f
                    listener?.onScrub(f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    progress = f
                    listener?.onScrubEnd(f)
                }
            }
            invalidate()
            return true
        }
    }

    companion object {
        private const val PURPLE = 0xFF7A59F3.toInt()
        private const val PURPLE_LIGHT = 0xFF9D85FF.toInt()
        private const val SKIP_MS = 10_000L
        private const val SCALE_FIT = 0
        private const val SCALE_FILL = 1
        private const val SCALE_STRETCH = 2
        private const val PREF_SCALE = "vibgram_player_scale"
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:10.0) Gecko/20150101 Firefox/47.0 (Chrome)"

        private val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )
        private val NETWORK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )

        /** Opens [message]'s video in this player. [meta] gives the backdrop/logo when known. */
        @JvmStatic
        @JvmOverloads
        fun open(fragment: BaseFragment?, message: MessageObject?, meta: VibeMovies.Meta? = null): Boolean {
            if (fragment == null || message == null || message.document == null) return false
            return fragment.presentFragment(VibePlayerActivity(message, meta))
        }
    }
}
