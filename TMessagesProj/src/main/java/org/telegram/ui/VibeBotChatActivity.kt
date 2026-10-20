package org.telegram.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.util.Linkify
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.VibeLayout
import org.telegram.messenger.browser.Browser
import org.telegram.messenger.vibgram.bot.BotButton
import org.telegram.messenger.vibgram.bot.BotChatUiState
import org.telegram.messenger.vibgram.bot.BotChatViewModel
import org.telegram.messenger.vibgram.bot.BotError
import org.telegram.messenger.vibgram.bot.BotMessage
import org.telegram.messenger.vibgram.bot.BotUiEvent
import org.telegram.messenger.vibgram.bot.BotVideoPlayback
import org.telegram.messenger.vibgram.bot.DownloadState
import org.telegram.messenger.vibgram.bot.ReplyKeyboard
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import kotlin.math.max

/**
 * Vibgram 🤖 Bots: talk to any bot or public chat by username, in Vibe's bubble-free layout.
 * Inline buttons, reply keyboards, and video replies (play right away, or download) all work here;
 * anything that needs Telegram's own flows (payments, phone/location requests) opens the full chat.
 *
 * Open with VibeBotChatActivity.newInstance("SomeBot") or with no username to pick one.
 */
class VibeBotChatActivity @JvmOverloads constructor(args: Bundle? = null) : BaseFragment(args) {

    private var holder: BotChatViewModel.Holder? = null
    private val vm: BotChatViewModel get() = holder!!.viewModel
    private lateinit var playback: BotVideoPlayback
    private var scope: CoroutineScope? = null
    private var viewJob: Job? = null

    // views
    private lateinit var column: LinearLayout
    private lateinit var searchPill: FrameLayout
    private lateinit var searchField: EditText
    private lateinit var titleBox: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var fullChatButton: ImageView
    private lateinit var listView: RecyclerListView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var adapter: MessagesAdapter
    private lateinit var statusBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var retryButton: TextView
    private lateinit var recentsRow: LinearLayout
    private lateinit var keyboardScroll: ScrollView
    private lateinit var keyboardPanel: LinearLayout
    private lateinit var inputBar: LinearLayout
    private lateinit var keyboardToggle: ImageView
    private lateinit var inputField: EditText
    private lateinit var startButton: TextView

    private var shownKeyboard: ReplyKeyboard? = null
    private var keyboardOpen = true
    private var lastStatus: BotChatUiState.Status? = null
    private var savedRecent: String? = null

    override fun onFragmentCreate(): Boolean {
        holder = BotChatViewModel.Holder(currentAccount)
        playback = BotVideoPlayback(currentAccount)
        scope = MainScope()
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        scope?.cancel()
        scope = null
        holder?.clear()
        holder = null
        super.onFragmentDestroy()
    }

    override fun isLightStatusBar(): Boolean = !Theme.isCurrentThemeDark()

    override fun createView(context: Context): View {
        actionBar.setAddToContainer(false)

        val root = FrameLayout(context)
        root.setBackgroundColor(VibeLayout.bg())
        fragmentView = root

        column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0)
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        column.addView(createTopBar(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64))
        column.addView(divider(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1))

        val middle = FrameLayout(context)
        column.addView(middle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
        adapter = MessagesAdapter()
        listView = RecyclerListView(context)
        listView.layoutManager = layoutManager
        listView.adapter = adapter
        listView.itemAnimator = null
        listView.clipToPadding = false
        listView.setPadding(0, dp(8f), 0, dp(8f))
        middle.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        middle.addView(createStatusBox(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER, 24f, 0f, 24f, 0f))

        keyboardPanel = LinearLayout(context)
        keyboardPanel.orientation = LinearLayout.VERTICAL
        keyboardPanel.setPadding(dp(12f), dp(8f), dp(12f), dp(4f))
        keyboardScroll = ScrollView(context)
        keyboardScroll.isVerticalScrollBarEnabled = false
        keyboardScroll.setBackgroundColor(VibeLayout.rail())
        keyboardScroll.addView(keyboardPanel)
        keyboardScroll.visibility = View.GONE
        column.addView(keyboardScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        column.addView(createInputBar(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        startButton = TextView(context).apply {
            text = "Start"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            typeface = VibeLayout.boldFont()
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(BLURPLE, 14f)
            visibility = View.GONE
            setOnClickListener { vm.startBot() }
        }
        column.addView(startButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16f, 8f, 16f, 12f))

        // Status/nav bars and the keyboard come in as insets (the app draws edge to edge).
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            column.setPadding(0, max(bars.top, AndroidUtilities.statusBarHeight), 0, max(bars.bottom, ime))
            WindowInsetsCompat.CONSUMED
        }

        bindViewModel()

        val initial = arguments?.getString(ARG_USERNAME)
        if (vm.state.value.target.isEmpty()) {
            if (!initial.isNullOrBlank()) vm.open(initial) else showSearch(focus = true)
        }
        return root
    }

    // ------------------------------------------------------------------ view model

    private fun bindViewModel() {
        viewJob?.cancel()
        val scope = scope ?: return
        viewJob = scope.launch {
            launch { vm.state.collect(::render) }
            launch { vm.events.collect(::handle) }
        }
    }

    private fun render(state: BotChatUiState) {
        val chat = state.chat

        // Top bar: search field until a chat is found, then its name.
        val searching = chat == null && state.status != BotChatUiState.Status.RESOLVING
        searchPill.visibility = if (searching) View.VISIBLE else View.GONE
        titleBox.visibility = if (searching) View.GONE else View.VISIBLE
        fullChatButton.visibility = if (chat != null) View.VISIBLE else View.GONE
        titleView.text = chat?.title ?: "@${state.target.removePrefix("@")}"
        subtitleView.text = when {
            chat == null -> "Finding…"
            chat.isBot -> "@${chat.username} · bot"
            chat.isChannel -> "@${chat.username} · channel"
            else -> "@${chat.username}"
        }
        if (chat != null && savedRecent != chat.username) {
            savedRecent = chat.username
            rememberRecent(chat.username)
        }

        // Messages.
        val wasAtBottom = isAtBottom()
        val previousLast = adapter.rows.lastOrNull()?.message?.id
        adapter.submit(state)
        val newLast = state.messages.lastOrNull()
        if (newLast != null && newLast.id != previousLast && (wasAtBottom || newLast.isOutgoing || previousLast == null)) {
            listView.post { if (adapter.itemCount > 0) listView.scrollToPosition(adapter.itemCount - 1) }
        }

        // Status in the middle when there's nothing to show.
        val (message, retry) = when (state.status) {
            BotChatUiState.Status.IDLE -> "Type the username of any bot or public chat." to false
            BotChatUiState.Status.RESOLVING -> "Finding @${state.target.removePrefix("@")}…" to false
            BotChatUiState.Status.LOADING_HISTORY -> "Loading messages…" to false
            BotChatUiState.Status.FAILED -> errorText(state.error) to true
            BotChatUiState.Status.READY ->
                (if (state.needsStart) "Press Start to begin talking to this bot." else "No messages yet. Say hi!") to false
        }
        val showStatus = state.messages.isEmpty()
        statusBox.visibility = if (showStatus) View.VISIBLE else View.GONE
        statusText.text = message
        retryButton.visibility = if (retry && state.target.isNotEmpty()) View.VISIBLE else View.GONE
        recentsRow.visibility = if (searching) View.VISIBLE else View.GONE
        if (searching && lastStatus != state.status) fillRecents()
        lastStatus = state.status

        // Input: Start button for fresh bots, otherwise the composer.
        val ready = chat != null
        startButton.visibility = if (ready && state.needsStart) View.VISIBLE else View.GONE
        inputBar.visibility = if (ready && !state.needsStart) View.VISIBLE else View.GONE
        inputField.hint = state.replyKeyboard?.placeholder ?: "Message ${chat?.title ?: ""}".trim()

        // Reply keyboard.
        if (state.replyKeyboard != shownKeyboard) {
            shownKeyboard = state.replyKeyboard
            keyboardOpen = true
            fillReplyKeyboard(state.replyKeyboard)
        }
        keyboardToggle.visibility = if (state.replyKeyboard != null) View.VISIBLE else View.GONE
        keyboardScroll.visibility = if (ready && state.replyKeyboard != null && keyboardOpen && !state.needsStart) View.VISIBLE else View.GONE
    }

    private fun handle(event: BotUiEvent) {
        val activity = parentActivity ?: return
        when (event) {
            is BotUiEvent.Toast -> bulletin(R.raw.info, event.text)
            is BotUiEvent.Alert -> showDialog(
                AlertDialog.Builder(activity)
                    .setTitle(vm.state.value.chat?.title ?: "")
                    .setMessage(event.text)
                    .setPositiveButton("OK", null)
                    .create()
            )
            is BotUiEvent.OpenUrl -> Browser.openUrl(activity, event.url)
            // Web apps and login links open in the in-app browser; the full chat has the native sheet.
            is BotUiEvent.OpenWebApp -> Browser.openUrl(activity, event.url)
            is BotUiEvent.CopyText -> {
                AndroidUtilities.addToClipboard(event.text)
                bulletin(R.raw.copy, "Copied")
            }
            is BotUiEvent.SwitchInline -> {
                bulletin(R.raw.info, "Inline mode works in the full chat")
                openFullChat()
            }
            is BotUiEvent.OpenProfile -> presentFragment(ProfileActivity(Bundle().apply { putLong("user_id", event.userId) }))
            is BotUiEvent.Play -> VibeMoviesActivity.play(this, event.message.source)
            is BotUiEvent.Error -> {
                // A failed open is already explained in the middle of the screen.
                if (vm.state.value.status != BotChatUiState.Status.FAILED) bulletin(R.raw.error, errorText(event.error))
            }
        }
    }

    // ------------------------------------------------------------------ top bar

    private fun createTopBar(context: Context): View {
        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(12f), dp(8f), dp(8f), dp(8f))

        val badge = FrameLayout(context)
        badge.background = rounded(BLURPLE, 14f)
        badge.addView(TextView(context).apply {
            text = "🤖"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f)
            gravity = Gravity.CENTER
        }, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        badge.setOnClickListener { finishFragment() }
        bar.addView(badge, LayoutHelper.createLinear(48, 48))

        val center = FrameLayout(context)
        bar.addView(center, LayoutHelper.createLinear(0, 48, 1f, 12, 0, 4, 0))

        searchPill = FrameLayout(context)
        searchPill.background = rounded(VibeLayout.card(), 14f)
        searchField = EditText(context).apply {
            background = null
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            typeface = VibeLayout.regular()
            setTextColor(VibeLayout.text())
            setHintTextColor(VibeLayout.text2())
            hint = "@username or t.me link"
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(16f), 0, dp(48f), 0)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    submitSearch()
                    true
                } else {
                    false
                }
            }
        }
        searchPill.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        searchPill.addView(icon(context, R.drawable.vibe_ic_search, VibeLayout.text()).apply {
            setOnClickListener { submitSearch() }
        }, LayoutHelper.createFrame(48f, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.RIGHT))
        center.addView(searchPill, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        titleBox = LinearLayout(context)
        titleBox.orientation = LinearLayout.VERTICAL
        titleBox.gravity = Gravity.CENTER_VERTICAL
        titleBox.setOnClickListener { showSearch(focus = true) } // tap the name to switch chats
        titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
            typeface = VibeLayout.boldFont()
            setTextColor(VibeLayout.text())
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(VibeLayout.text2())
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        titleBox.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        titleBox.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        titleBox.visibility = View.GONE
        center.addView(titleBox, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        fullChatButton = icon(context, R.drawable.vibe_ic_chat, VibeLayout.text()).apply {
            visibility = View.GONE
            contentDescription = "Open full chat"
            setOnClickListener { openFullChat() }
        }
        bar.addView(fullChatButton, LayoutHelper.createLinear(44, 44))
        return bar
    }

    private fun submitSearch() {
        val text = searchField.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        AndroidUtilities.hideKeyboard(searchField)
        searchField.setText("")
        shownKeyboard = null
        vm.open(text)
    }

    private fun showSearch(focus: Boolean) {
        searchPill.visibility = View.VISIBLE
        titleBox.visibility = View.GONE
        recentsRow.visibility = View.VISIBLE
        fillRecents()
        if (focus) {
            searchField.requestFocus()
            AndroidUtilities.runOnUIThread({ AndroidUtilities.showKeyboard(searchField) }, 200)
        }
    }

    private fun openFullChat() {
        val chat = vm.state.value.chat ?: return
        val args = Bundle()
        if (chat.chatId > 0) args.putLong("user_id", chat.chatId) else args.putLong("chat_id", -chat.chatId)
        presentFragment(ChatActivity(args))
    }

    // ------------------------------------------------------------------ status + recents

    private fun createStatusBox(context: Context): View {
        statusBox = LinearLayout(context)
        statusBox.orientation = LinearLayout.VERTICAL
        statusBox.gravity = Gravity.CENTER_HORIZONTAL

        statusText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTextColor(VibeLayout.text2())
            gravity = Gravity.CENTER
        }
        statusBox.addView(statusText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        retryButton = pillButton(context, "Try again", filled = true).apply {
            visibility = View.GONE
            setOnClickListener { vm.retry() }
        }
        statusBox.addView(retryButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40, Gravity.CENTER_HORIZONTAL, 0, 14, 0, 0))

        recentsRow = LinearLayout(context)
        recentsRow.orientation = LinearLayout.VERTICAL
        recentsRow.gravity = Gravity.CENTER_HORIZONTAL
        statusBox.addView(recentsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 16f, 0f, 0f))
        return statusBox
    }

    private fun fillRecents() {
        val context = recentsRow.context
        recentsRow.removeAllViews()
        val recents = loadRecents()
        if (recents.isEmpty()) return
        recentsRow.addView(TextView(context).apply {
            text = "Recent"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            typeface = VibeLayout.boldFont()
            setTextColor(VibeLayout.text2())
            gravity = Gravity.CENTER
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 8f))
        val scroller = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        recents.forEach { name ->
            chips.addView(pillButton(context, "@$name", filled = false).apply {
                setOnClickListener {
                    AndroidUtilities.hideKeyboard(searchField)
                    shownKeyboard = null
                    vm.open(name)
                }
                setOnLongClickListener {
                    forgetRecent(name)
                    fillRecents()
                    true
                }
            }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 4f, 0f, 4f, 0f))
        }
        scroller.addView(chips)
        recentsRow.addView(scroller, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
    }

    private fun loadRecents(): List<String> =
        MessagesController.getGlobalMainSettings().getString(PREF_RECENTS, "").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun rememberRecent(username: String) {
        val list = listOf(username) + loadRecents().filterNot { it.equals(username, ignoreCase = true) }
        MessagesController.getGlobalMainSettings().edit().putString(PREF_RECENTS, list.take(MAX_RECENTS).joinToString(",")).apply()
    }

    private fun forgetRecent(username: String) {
        val list = loadRecents().filterNot { it.equals(username, ignoreCase = true) }
        MessagesController.getGlobalMainSettings().edit().putString(PREF_RECENTS, list.joinToString(",")).apply()
    }

    // ------------------------------------------------------------------ input + reply keyboard

    private fun createInputBar(context: Context): View {
        inputBar = LinearLayout(context)
        inputBar.orientation = LinearLayout.HORIZONTAL
        inputBar.gravity = Gravity.BOTTOM
        inputBar.setPadding(dp(12f), dp(8f), dp(12f), dp(10f))
        inputBar.visibility = View.GONE

        keyboardToggle = icon(context, R.drawable.input_keyboard, VibeLayout.text2()).apply {
            visibility = View.GONE
            contentDescription = "Bot keyboard"
            setOnClickListener {
                keyboardOpen = !keyboardOpen
                render(vm.state.value)
            }
        }
        inputBar.addView(keyboardToggle, LayoutHelper.createLinear(44, 44))

        val pill = FrameLayout(context)
        pill.background = rounded(VibeLayout.card(), 22f)
        inputField = EditText(context).apply {
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            typeface = VibeLayout.regular()
            setTextColor(VibeLayout.text())
            setHintTextColor(VibeLayout.text2())
            maxLines = 5
            minHeight = dp(44f)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        pill.addView(inputField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))
        inputBar.addView(pill, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 8, 0))

        val send = FrameLayout(context)
        val sendBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BLURPLE)
        }
        send.background = sendBg
        send.addView(icon(context, R.drawable.vibe_ic_send, 0xFFFFFFFF.toInt()), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        send.contentDescription = "Send"
        send.setOnClickListener {
            val text = inputField.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                vm.send(text)
                inputField.setText("")
            }
        }
        inputBar.addView(send, LayoutHelper.createLinear(44, 44))
        return inputBar
    }

    private fun fillReplyKeyboard(keyboard: ReplyKeyboard?) {
        keyboardPanel.removeAllViews()
        keyboard ?: return
        val context = keyboardPanel.context
        keyboard.rows.forEach { row ->
            val line = LinearLayout(context)
            line.orientation = LinearLayout.HORIZONTAL
            row.forEach { key ->
                line.addView(keyButton(context, key.text).apply {
                    setOnClickListener { vm.onReplyButton(key) }
                }, LayoutHelper.createLinear(0, if (keyboard.resize) 40 else 48, 1f, 3, 3, 3, 3))
            }
            keyboardPanel.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        // Keep big keyboards from pushing the chat off screen.
        keyboardScroll.layoutParams = keyboardScroll.layoutParams?.apply {
            height = if (keyboard.rows.size > 4) dp(232f) else ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }

    // ------------------------------------------------------------------ message list

    private data class Row(
        val message: BotMessage,
        val download: DownloadState?,
        val pending: Set<String>,
        val sender: String,
        val showHeader: Boolean,
    )

    private inner class MessagesAdapter : RecyclerListView.SelectionAdapter() {
        var rows: List<Row> = emptyList()
            private set

        init {
            setHasStableIds(true)
        }

        fun submit(state: BotChatUiState) {
            val chatName = state.chat?.title ?: ""
            var prev: BotMessage? = null
            val next = state.messages.map { m ->
                val keys = state.pendingButtons.filterTo(HashSet()) { it.startsWith("${m.id}:") }
                // Group consecutive messages from the same side within 5 minutes under one name.
                val header = prev == null || prev!!.isOutgoing != m.isOutgoing || m.date - prev!!.date > 300
                prev = m
                Row(m, state.downloads[m.id], keys, if (m.isOutgoing) "You" else chatName, header)
            }
            val old = rows
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o].message.id == next[n].message.id
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == next[n]
            })
            rows = next
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemId(position: Int): Long = rows[position].message.id.toLong()
        override fun getItemCount(): Int = rows.size
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = false

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell = MessageCell(parent.context)
            cell.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(cell)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as MessageCell).bind(rows[position])
        }
    }

    /** One message, Discord-style: name + time, text, video card, inline buttons. */
    private inner class MessageCell(context: Context) : LinearLayout(context) {
        private val header = LinearLayout(context)
        private val name = TextView(context)
        private val time = TextView(context)
        private val body = TextView(context)
        private val videoCard = FrameLayout(context)
        private val thumb = BackupImageView(context)
        private val play = FrameLayout(context)
        private val meta = TextView(context)
        private val downloadButton = FrameLayout(context)
        private val downloadIcon = ImageView(context)
        private val downloadText = TextView(context)
        private val fileName = TextView(context)
        private val buttons = LinearLayout(context)
        private val sendState = TextView(context)

        init {
            orientation = VERTICAL
            setPadding(dp(16f), 0, dp(16f), dp(4f))

            header.orientation = HORIZONTAL
            header.gravity = Gravity.BOTTOM
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            name.typeface = VibeLayout.boldFont()
            name.isSingleLine = true
            name.ellipsize = TextUtils.TruncateAt.END
            time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            time.setTextColor(VibeLayout.text2())
            header.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 1f))
            header.addView(time, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8f, 0f, 0f, 1f))
            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 10f, 0f, 2f))

            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            body.setTextColor(VibeLayout.text())
            body.setLinkTextColor(BLURPLE)
            body.typeface = VibeLayout.regular()
            body.autoLinkMask = Linkify.WEB_URLS
            body.setOnLongClickListener {
                AndroidUtilities.addToClipboard(body.text)
                bulletin(R.raw.copy, "Text copied")
                true
            }
            addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

            // Video card: thumbnail, play in the middle, duration/size bottom-left, download top-right.
            videoCard.background = rounded(VibeLayout.card(), 12f)
            videoCard.clipToOutline = true
            thumb.setRoundRadius(dp(12f))
            videoCard.addView(thumb, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
            play.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC000000.toInt())
            }
            play.addView(icon(context, R.drawable.ic_action_play, 0xFFFFFFFF.toInt()), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
            videoCard.addView(play, LayoutHelper.createFrame(52f, 52f, Gravity.CENTER))
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            meta.setTextColor(0xFFFFFFFF.toInt())
            meta.typeface = VibeLayout.boldFont()
            meta.setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            meta.background = rounded(0x99000000.toInt(), 8f)
            videoCard.addView(meta, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.BOTTOM or Gravity.LEFT, 8f, 0f, 0f, 8f))
            downloadButton.background = rounded(0x99000000.toInt(), 16f)
            downloadIcon.scaleType = ImageView.ScaleType.CENTER
            downloadButton.addView(downloadIcon, LayoutHelper.createFrame(32f, 32f, Gravity.LEFT or Gravity.CENTER_VERTICAL))
            downloadText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            downloadText.setTextColor(0xFFFFFFFF.toInt())
            downloadText.typeface = VibeLayout.boldFont()
            downloadButton.addView(downloadText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.LEFT or Gravity.CENTER_VERTICAL, 30f, 0f, 10f, 0f))
            videoCard.addView(downloadButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32f, Gravity.TOP or Gravity.RIGHT, 0f, 8f, 8f, 0f))
            addView(videoCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 180, 0f, 6f, 0f, 0f))

            fileName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            fileName.setTextColor(VibeLayout.text2())
            fileName.isSingleLine = true
            fileName.ellipsize = TextUtils.TruncateAt.MIDDLE
            addView(fileName, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))

            buttons.orientation = VERTICAL
            addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 6f, 0f, 0f))

            sendState.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            addView(sendState, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
        }

        fun bind(row: Row) {
            val m = row.message

            header.visibility = if (row.showHeader) View.VISIBLE else View.GONE
            name.text = row.sender
            name.setTextColor(if (m.isOutgoing) VibeLayout.text() else BLURPLE)
            time.text = if (m.date > 0) LocaleController.getInstance().getFormatterDay().format(m.date * 1000L) +
                (if (m.editDate > 0) " · edited" else "") else ""

            body.text = m.text
            body.visibility = if (m.text.isEmpty()) View.GONE else View.VISIBLE

            bindVideo(m, row.download)
            bindButtons(m, row.pending)

            when {
                m.sendFailed -> {
                    sendState.visibility = View.VISIBLE
                    sendState.text = "Not sent"
                    sendState.setTextColor(0xFFED4245.toInt())
                }
                m.isSending -> {
                    sendState.visibility = View.VISIBLE
                    sendState.text = "Sending…"
                    sendState.setTextColor(VibeLayout.text2())
                }
                else -> sendState.visibility = View.GONE
            }
        }

        private fun bindVideo(m: BotMessage, download: DownloadState?) {
            val video = m.video
            if (video == null) {
                videoCard.visibility = View.GONE
                fileName.visibility = View.GONE
                return
            }
            videoCard.visibility = View.VISIBLE
            val ratio = if (video.width > 0 && video.height > 0) video.height.toFloat() / video.width else 9f / 16f
            val cardHeight = (listView.width.takeIf { it > 0 } ?: AndroidUtilities.displaySize.x).let { w ->
                ((w - dp(32f)) * ratio).toInt().coerceIn(dp(140f), dp(320f))
            }
            if (videoCard.layoutParams.height != cardHeight) {
                videoCard.layoutParams = videoCard.layoutParams.apply { height = cardHeight }
            }

            val placeholder = video.thumbnail?.let(::inlineThumb)
            val location = playback.thumbnailLocation(m)
            if (location != null) {
                thumb.setImage(location, "${dp(320f)}_${dp(180f)}", placeholder, m.source)
            } else {
                thumb.setImageDrawable(placeholder)
            }

            val parts = ArrayList<String>(2)
            if (video.durationSeconds > 0) parts += AndroidUtilities.formatShortDuration(video.durationSeconds.toInt())
            if (video.sizeBytes > 0) parts += AndroidUtilities.formatFileSize(video.sizeBytes)
            meta.text = parts.joinToString(" · ")
            meta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

            val downloaded = download is DownloadState.Completed || (download == null && playback.localFile(m) != null)
            when {
                download is DownloadState.Downloading -> {
                    downloadIcon.setImageResource(R.drawable.vibe_ic_close)
                    downloadText.text = "${(download.fraction * 100).toInt()}%"
                    downloadButton.setOnClickListener { vm.cancelDownload(m) }
                }
                downloaded -> {
                    downloadIcon.setImageResource(R.drawable.vibe_ic_done)
                    downloadText.text = "Saved"
                    downloadButton.setOnClickListener(null)
                }
                else -> {
                    downloadIcon.setImageResource(R.drawable.vibe_ic_download)
                    downloadText.text = "Download"
                    downloadButton.setOnClickListener { vm.download(m) }
                }
            }
            downloadIcon.colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)

            videoCard.setOnClickListener { vm.play(m) }
            play.setOnClickListener { vm.play(m) }

            fileName.visibility = if (video.isDocument && video.fileName.isNotEmpty()) View.VISIBLE else View.GONE
            fileName.text = video.fileName
        }

        private fun bindButtons(m: BotMessage, pending: Set<String>) {
            buttons.removeAllViews()
            if (m.inlineKeyboard.isEmpty()) {
                buttons.visibility = View.GONE
                return
            }
            buttons.visibility = View.VISIBLE
            m.inlineKeyboard.forEach { row ->
                val line = LinearLayout(context)
                line.orientation = HORIZONTAL
                row.forEach { button ->
                    val waiting = "${m.id}:${button.row}:${button.column}" in pending
                    val label = when {
                        waiting -> "…"
                        button is BotButton.Url || button is BotButton.LoginUrl -> "${button.text} ↗"
                        button is BotButton.WebApp -> "${button.text} ⧉"
                        button is BotButton.Copy -> "${button.text} ⎘"
                        else -> button.text
                    }
                    line.addView(keyButton(context, label).apply {
                        alpha = if (button is BotButton.Disabled) 0.4f else 1f
                        isEnabled = button !is BotButton.Disabled
                        setOnClickListener { vm.onInlineButton(m, button) }
                    }, LayoutHelper.createLinear(0, 40, 1f, 2, 2, 2, 2))
                }
                buttons.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun inlineThumb(t: org.telegram.messenger.vibgram.bot.BotThumbnail): Drawable? {
        val bytes = t.inlineBytes ?: return null
        val bitmap = try {
            if (t.isStripped) ImageLoader.getStrippedPhotoBitmap(bytes, "b") else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        } ?: return null
        return BitmapDrawable(listView.resources, bitmap)
    }

    private fun isAtBottom(): Boolean {
        val last = layoutManager.findLastVisibleItemPosition()
        return last == RecyclerView.NO_POSITION || last >= adapter.itemCount - 2
    }

    private fun errorText(error: BotError?): String = when (error) {
        null -> "Something went wrong."
        is BotError.UsernameNotFound -> "Nobody uses @${error.username.ifEmpty { vm.state.value.target.removePrefix("@") }}."
        is BotError.FloodWait -> "Too many requests. Try again in ${error.seconds} s."
        BotError.BotTimeout -> "The bot didn't answer in time."
        BotError.ButtonExpired -> "That button is no longer active."
        BotError.PasswordRequired -> "This button needs your password. Use the full chat."
        is BotError.ChatUnavailable -> "You can't message this chat."
        is BotError.Network -> "No connection. Check your internet and try again."
        is BotError.Download -> "Download failed: ${error.reason}."
        BotError.NoChat -> "Open a chat first."
        is BotError.Server -> "Telegram error: ${error.text.ifEmpty { error.code.toString() }}"
        is BotError.Unexpected -> "Something went wrong."
    }

    private fun bulletin(raw: Int, text: String) {
        if (parentActivity == null) return
        BulletinFactory.of(this).createSimpleBulletin(raw, text).show()
    }

    private fun keyButton(context: Context, label: String) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        typeface = VibeLayout.boldFont()
        setTextColor(VibeLayout.text())
        setPadding(dp(8f), 0, dp(8f), 0)
        background = Theme.createSimpleSelectorRoundRectDrawable(dp(10f), VibeLayout.cardHigh(), Theme.multAlpha(VibeLayout.text(), 0.15f))
    }

    private fun pillButton(context: Context, label: String, filled: Boolean) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        typeface = VibeLayout.boldFont()
        setTextColor(if (filled) 0xFFFFFFFF.toInt() else VibeLayout.text())
        setPadding(dp(16f), 0, dp(16f), 0)
        background = rounded(if (filled) BLURPLE else VibeLayout.card(), 18f)
    }

    private fun icon(context: Context, res: Int, color: Int) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER
        setImageResource(res)
        colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    private fun divider(context: Context) = View(context).apply { setBackgroundColor(VibeLayout.divider()) }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    companion object {
        private const val BLURPLE = 0xFF5865F2.toInt()
        private const val ARG_USERNAME = "username"
        private const val PREF_RECENTS = "vibgram_bot_recents"
        private const val MAX_RECENTS = 8

        @JvmStatic
        fun newInstance(username: String?): VibeBotChatActivity =
            VibeBotChatActivity(Bundle().apply { if (!username.isNullOrBlank()) putString(ARG_USERNAME, username) })
    }
}
