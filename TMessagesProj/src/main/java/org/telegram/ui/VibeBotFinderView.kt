package org.telegram.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.messenger.VibeLayout
import org.telegram.messenger.VibeMovies
import org.telegram.messenger.browser.Browser
import org.telegram.messenger.vibgram.bot.BotButton
import org.telegram.messenger.vibgram.bot.BotChatRepository
import org.telegram.messenger.vibgram.bot.BotError
import org.telegram.messenger.vibgram.bot.BotException
import org.telegram.messenger.vibgram.bot.BotMessage
import org.telegram.messenger.vibgram.bot.BotUpdate
import org.telegram.messenger.vibgram.bot.ReplyButton
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.LayoutHelper

/**
 * "From bots" on a movie page: asks one of your bots for the title and shows its answers right
 * here, so you never leave the page.
 *
 * - Pick a bot (the last one you used is preselected) and tap Ask; the title is pre-filled.
 * - Videos the bot sends appear as rows: tap to play in the movie player (streams right away).
 * - The bot's buttons (quality, parts, "get file"…) are pressed here; /commands in its text are
 *   tappable; t.me/SomeBot?start=… links (how many bots hand over files) are followed in the
 *   background by starting that bot, and its answer shows up here too.
 * - Links to channels the bot wants you to join open normally; come back and tap Ask again.
 */
@SuppressLint("ViewConstructor")
class VibeBotFinderView(
    context: Context,
    private val account: Int,
    private val meta: VibeMovies.Meta,
    private val listener: Listener,
) : LinearLayout(context) {

    fun interface Listener {
        /** A video was picked: play it. */
        fun onPlay(message: MessageObject)
    }

    private val repository = BotChatRepository(account)
    private val scope: CoroutineScope = MainScope()
    private val watched = HashMap<Long, Job>()
    private var timeoutJob: Job? = null

    private var bots: List<TLRPC.User> = emptyList()
    private var selectedBot: TLRPC.User? = null
    private var askedAt = 0
    private val answers = LinkedHashMap<String, BotMessage>() // "chatId:id" -> latest version
    private val pressing = HashSet<String>()

    private val botChips = LinearLayout(context)
    private val queryField = EditText(context)
    private val status = TextView(context)
    private val results = LinearLayout(context)

    init {
        orientation = VERTICAL

        addView(TextView(context).apply {
            text = "FROM YOUR BOTS"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            typeface = VibeLayout.boldFont()
            setTextColor(VibeLayout.text2())
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8f, 0f, 8f, 8f))

        val chipsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        botChips.orientation = HORIZONTAL
        chipsScroll.addView(botChips)
        addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4f, 0f, 4f, 0f))

        // Query + Ask
        val queryRow = LinearLayout(context)
        queryRow.orientation = HORIZONTAL
        queryRow.gravity = Gravity.CENTER_VERTICAL
        val pill = FrameLayout(context)
        pill.background = rounded(VibeLayout.card(), 12f)
        queryField.apply {
            background = null
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            typeface = VibeLayout.regular()
            setTextColor(VibeLayout.text())
            setHintTextColor(VibeLayout.text2())
            hint = "What to ask for"
            setText(meta.name.orEmpty())
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(14f), 0, dp(14f), 0)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    ask()
                    true
                } else {
                    false
                }
            }
        }
        pill.addView(queryField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        queryRow.addView(pill, LayoutHelper.createLinear(0, 44, 1f))
        queryRow.addView(TextView(context).apply {
            text = "Ask"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            typeface = VibeLayout.boldFont()
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(18f), 0, dp(18f), 0)
            background = rounded(BLURPLE, 12f)
            setOnClickListener { ask() }
        }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, 8f, 0f, 0f, 0f))
        addView(queryRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8f, 10f, 8f, 0f))

        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        status.setTextColor(VibeLayout.text2())
        status.visibility = View.GONE
        addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8f, 8f, 8f, 0f))

        results.orientation = VERTICAL
        addView(results, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 6f, 0f, 0f))

        loadBots()
    }

    /** Stop listening; call when the page closes. */
    fun release() {
        scope.cancel()
        watched.clear()
    }

    // ------------------------------------------------------------------ bots

    private fun loadBots() {
        bots = VibeMovies.myBots(account)
        val lastId = MessagesController.getGlobalMainSettings().getLong(PREF_LAST_BOT, 0L)
        selectedBot = bots.firstOrNull { it.id == lastId } ?: bots.firstOrNull()
        fillBotChips()
        if (bots.isEmpty()) {
            setStatus("No bots yet. Open a bot once in 🤖 Bots (Movies top bar) and it shows up here.")
        }
    }

    private fun fillBotChips() {
        botChips.removeAllViews()
        bots.forEach { bot ->
            val selected = bot.id == selectedBot?.id
            botChips.addView(TextView(context).apply {
                text = bot.username?.let { "@$it" } ?: UserObject.getUserName(bot)
                gravity = Gravity.CENTER
                isSingleLine = true
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                typeface = VibeLayout.boldFont()
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else VibeLayout.text())
                setPadding(dp(14f), 0, dp(14f), 0)
                background = rounded(if (selected) BLURPLE else VibeLayout.card(), 18f)
                setOnClickListener {
                    selectedBot = bot
                    MessagesController.getGlobalMainSettings().edit().putLong(PREF_LAST_BOT, bot.id).apply()
                    fillBotChips()
                }
            }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 4f, 0f, 4f, 0f))
        }
    }

    // ------------------------------------------------------------------ asking

    /** Asks the selected bot for the query in the field (the title by default). */
    fun ask() {
        val bot = selectedBot ?: return setStatus("Pick a bot first.")
        val query = queryField.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return
        AndroidUtilities.hideKeyboard(queryField)
        answers.clear()
        pressing.clear()
        renderAnswers()
        askedAt = nowSeconds()
        watch(bot.id)
        setStatus("Asking @${bot.username ?: UserObject.getUserName(bot)}…")
        scope.launch {
            try {
                repository.sendText(bot.id, query)
                setStatus("Waiting for @${bot.username ?: "bot"}…")
                expectReply("@${bot.username ?: "The bot"}")
            } catch (e: BotException) {
                setStatus(errorText(e.error))
            }
        }
    }

    /** Listens to a bot chat for the rest of this page's life. */
    private fun watch(chatId: Long) {
        if (watched[chatId]?.isActive == true) return
        watched[chatId] = scope.launch {
            try {
                repository.updates(chatId).collect { onUpdate(it) }
            } catch (e: BotException) {
                setStatus(errorText(e.error))
            }
        }
    }

    private fun onUpdate(update: BotUpdate) {
        when (update) {
            is BotUpdate.NewMessages -> update.messages.forEach(::accept)
            is BotUpdate.EditedMessages -> update.messages.forEach { m ->
                // Bots edit their result message to show the next page or the quality list.
                if (answers.containsKey(key(m))) answers[key(m)] = m else accept(m)
            }
            is BotUpdate.DeletedMessages -> {
                val gone = update.ids.toHashSet()
                answers.keys.removeAll { it.substringAfter(':').toIntOrNull() in gone }
            }
            else -> return
        }
        renderAnswers()
    }

    private fun accept(m: BotMessage) {
        if (m.isOutgoing || askedAt == 0 || m.date < askedAt - 5) return
        answers[key(m)] = m
        timeoutJob?.cancel()
        val videos = answers.values.count { it.video != null }
        setStatus(
            if (videos > 0) "Tap a video to play it."
            else "Pick an option below."
        )
    }

    private fun expectReply(who: String) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(REPLY_TIMEOUT_MS)
            if (answers.isEmpty()) setStatus("$who hasn't answered yet. Try again, or pick another bot.")
        }
    }

    // ------------------------------------------------------------------ actions on answers

    private fun press(message: BotMessage, button: BotButton) {
        when (button) {
            is BotButton.Callback, is BotButton.Game -> {
                val k = "${key(message)}:${button.row}:${button.column}"
                if (!pressing.add(k)) return
                renderAnswers()
                setStatus("Waiting for the bot…")
                scope.launch {
                    try {
                        val answer = repository.pressCallbackButton(message, button)
                        when {
                            answer.url != null -> openLink(answer.url)
                            answer.message != null -> setStatus(answer.message)
                        }
                        expectReply("The bot")
                    } catch (e: BotException) {
                        if (e.error !is BotError.BotTimeout) setStatus(errorText(e.error))
                    } finally {
                        pressing.remove(k)
                        renderAnswers()
                    }
                }
            }
            is BotButton.Url -> openLink(button.url)
            is BotButton.LoginUrl -> Browser.openUrl(context, button.url)
            is BotButton.WebApp -> Browser.openUrl(context, button.url)
            is BotButton.Copy -> {
                AndroidUtilities.addToClipboard(button.copyText)
                setStatus("Copied.")
            }
            else -> setStatus("Open the bot in 🤖 Bots for this button.")
        }
    }

    /** Sends a /command or a reply-keyboard key to the bot that showed it. */
    private fun sendToBot(chatId: Long, text: String) {
        setStatus("Sending $text…")
        scope.launch {
            try {
                repository.sendText(chatId, text)
                expectReply("The bot")
            } catch (e: BotException) {
                setStatus(errorText(e.error))
            }
        }
    }

    /**
     * t.me/SomeBot?start=xyz (or tg://resolve?domain=SomeBot&start=xyz): start that bot here and
     * show its answer. Anything else (a channel to join, a website) opens normally.
     */
    private fun openLink(url: String) {
        val link = parseTelegramLink(url) ?: return Browser.openUrl(context, url)
        scope.launch {
            val chat = try {
                repository.resolve(link.first)
            } catch (e: BotException) {
                null
            }
            if (chat == null || !chat.isBot) {
                Browser.openUrl(context, url)
                return@launch
            }
            if (askedAt == 0) askedAt = nowSeconds()
            watch(chat.chatId)
            setStatus("Getting it from @${chat.username}…")
            try {
                repository.startBot(chat.chatId, link.second)
                expectReply("@${chat.username}")
            } catch (e: BotException) {
                setStatus(errorText(e.error))
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun renderAnswers() {
        results.removeAllViews()
        answers.values.forEach { m ->
            m.video?.let { results.addView(videoRow(m), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 6f, 0f, 0f)) }
            if (m.text.isNotBlank()) {
                results.addView(answerText(m), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8f, 6f, 8f, 0f))
            }
            m.inlineKeyboard.forEach { row ->
                val line = LinearLayout(context)
                line.orientation = HORIZONTAL
                row.forEach { button ->
                    val busy = "${key(m)}:${button.row}:${button.column}" in pressing
                    line.addView(chip(if (busy) "…" else label(button)) { press(m, button) }.apply {
                        alpha = if (button is BotButton.Disabled) 0.4f else 1f
                        isEnabled = button !is BotButton.Disabled && !busy
                    }, LayoutHelper.createLinear(0, 40, 1f, 3, 3, 3, 3))
                }
                results.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4f, 2f, 4f, 0f))
            }
            m.replyKeyboard?.rows?.forEach { row ->
                val line = LinearLayout(context)
                line.orientation = HORIZONTAL
                row.filter { it.kind == ReplyButton.Kind.TEXT }.forEach { key ->
                    line.addView(chip(key.text) { sendToBot(m.chatId, key.text) }, LayoutHelper.createLinear(0, 40, 1f, 3, 3, 3, 3))
                }
                if (line.childCount > 0) results.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4f, 2f, 4f, 0f))
            }
        }
    }

    private fun videoRow(m: BotMessage): View {
        val video = m.video!!
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        row.background = rounded(VibeLayout.card(), 12f)

        row.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_action_play)
            colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BLURPLE)
            }
        }, LayoutHelper.createLinear(40, 40))

        val column = LinearLayout(context)
        column.orientation = VERTICAL
        column.addView(TextView(context).apply {
            text = video.fileName.ifEmpty { m.text.lineSequence().firstOrNull().orEmpty().ifEmpty { meta.name.orEmpty() } }
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            typeface = VibeLayout.boldFont()
            setTextColor(VibeLayout.text())
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        val facts = ArrayList<String>(3)
        if (video.sizeBytes > 0) facts += AndroidUtilities.formatFileSize(video.sizeBytes)
        if (video.durationSeconds > 0) facts += AndroidUtilities.formatShortDuration(video.durationSeconds.toInt())
        if (video.height > 0) facts += "${video.height}p"
        column.addView(TextView(context).apply {
            text = facts.joinToString(" · ")
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(VibeLayout.text2())
            isSingleLine = true
        })
        row.addView(column, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0))
        row.setOnClickListener { m.source?.let(listener::onPlay) }
        return row
    }

    /** The bot's text with /commands and t.me links tappable. */
    private fun answerText(m: BotMessage): TextView {
        val spannable = SpannableString(m.text)
        COMMAND.findAll(m.text).forEach { match ->
            val command = match.value
            spannable.setSpan(link { sendToBot(m.chatId, command) }, match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        TG_LINK.findAll(m.text).forEach { match ->
            val url = match.value
            spannable.setSpan(link { openLink(url) }, match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return TextView(context).apply {
            text = spannable
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(VibeLayout.text())
            setLinkTextColor(BLURPLE)
            maxLines = 12
            ellipsize = TextUtils.TruncateAt.END
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun link(onClick: () -> Unit) = object : ClickableSpan() {
        override fun onClick(widget: View) = onClick()
        override fun updateDrawState(ds: TextPaint) {
            ds.color = BLURPLE
            ds.isUnderlineText = false
        }
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        typeface = VibeLayout.boldFont()
        setTextColor(VibeLayout.text())
        setPadding(dp(8f), 0, dp(8f), 0)
        background = rounded(VibeLayout.cardHigh(), 10f)
        setOnClickListener { onClick() }
    }

    private fun label(button: BotButton) = when (button) {
        is BotButton.Url, is BotButton.LoginUrl -> "${button.text} ↗"
        else -> button.text
    }

    private fun setStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun errorText(error: BotError): String = when (error) {
        is BotError.FloodWait -> "Too many requests. Try again in ${error.seconds} s."
        BotError.BotTimeout -> "The bot is slow to answer. Wait a moment."
        BotError.ButtonExpired -> "That option expired. Tap Ask again."
        is BotError.ChatUnavailable -> "Can't message this bot (blocked or unavailable)."
        is BotError.Network -> "No connection."
        is BotError.UsernameNotFound -> "That bot doesn't exist anymore."
        else -> "Something went wrong."
    }

    private fun key(m: BotMessage) = "${m.chatId}:${m.id}"

    private fun nowSeconds() = (System.currentTimeMillis() / 1000).toInt()

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    companion object {
        private const val BLURPLE = 0xFF5865F2.toInt()
        private const val PREF_LAST_BOT = "vibgram_movie_bot"
        private const val REPLY_TIMEOUT_MS = 30_000L

        private val COMMAND = Regex("(?<![\\w/])/[A-Za-z0-9_]{2,64}(@[A-Za-z0-9_]+)?")
        private val TG_LINK = Regex("(https?://)?(t\\.me|telegram\\.me)/[A-Za-z0-9_]+(\\?[A-Za-z0-9_=&%-]+)?")

        /** (username, start parameter) from a t.me / tg://resolve link, or null for other links. */
        @JvmStatic
        fun parseTelegramLink(url: String): Pair<String, String?>? {
            val uri = try {
                Uri.parse(if (url.startsWith("t.me") || url.startsWith("telegram.me")) "https://$url" else url)
            } catch (e: Exception) {
                return null
            }
            val host = uri.host?.lowercase()
            val name: String?
            when {
                uri.scheme == "tg" && host == "resolve" -> name = uri.getQueryParameter("domain")
                host == "t.me" || host == "telegram.me" || host == "www.t.me" -> {
                    val first = uri.pathSegments.firstOrNull()
                    // joinchat / +invite / c/123 / s/channel are not bots.
                    name = if (first == null || first.startsWith("+") || first in setOf("joinchat", "c", "s", "addstickers", "share")) null else first
                }
                else -> name = null
            }
            if (name.isNullOrBlank()) return null
            return name to uri.getQueryParameter("start")
        }
    }
}
