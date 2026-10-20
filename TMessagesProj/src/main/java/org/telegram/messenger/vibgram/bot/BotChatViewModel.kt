package org.telegram.messenger.vibgram.bot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.telegram.messenger.FileLoader
import org.telegram.messenger.UserConfig

/** Everything the bot chat screen renders. Immutable; replaced atomically on every change. */
data class BotChatUiState(
    val target: String = "",
    val status: Status = Status.IDLE,
    val chat: BotChat? = null,
    /** Oldest first, keyed by message id, capped at [BotChatViewModel.MAX_MESSAGES]. */
    val messages: List<BotMessage> = emptyList(),
    /** Latest reply keyboard the bot is showing (null = system keyboard). */
    val replyKeyboard: ReplyKeyboard? = null,
    /** "${messageId}:${row}:${column}" of callback buttons waiting for the bot's answer. */
    val pendingButtons: Set<String> = emptySet(),
    /** Per message id. */
    val downloads: Map<Int, DownloadState> = emptyMap(),
    /** A bot with no history yet: show a Start button that calls [BotChatViewModel.startBot]. */
    val needsStart: Boolean = false,
    /** Last non-fatal error, cleared by [BotChatViewModel.clearError]. */
    val error: BotError? = null,
) {
    enum class Status { IDLE, RESOLVING, LOADING_HISTORY, READY, FAILED }

    fun isPending(message: BotMessage, button: BotButton) = buttonKey(message.id, button) in pendingButtons
}

/** One-shot things the screen should do (not state). */
sealed class BotUiEvent {
    data class Toast(val text: String) : BotUiEvent()
    data class Alert(val text: String) : BotUiEvent()
    /** Open with Browser.openUrl(context, url) — handles t.me links in-app. */
    data class OpenUrl(val url: String) : BotUiEvent()
    /** Open a bot Web App / login URL with the app's existing web view flow. */
    data class OpenWebApp(val url: String, val isLogin: Boolean) : BotUiEvent()
    data class CopyText(val text: String) : BotUiEvent()
    /** Inline mode: put "@bot query" in the input field (samePeer) or pick a chat first. */
    data class SwitchInline(val botUsername: String, val query: String, val samePeer: Boolean) : BotUiEvent()
    data class OpenProfile(val userId: Long) : BotUiEvent()
    /** Play with a player from BotVideoPlayback.buildPlayer(context) so tg:// URIs work. */
    data class Play(val message: BotMessage, val mediaItem: MediaItem) : BotUiEvent()
    data class Error(val error: BotError) : BotUiEvent()
}

internal fun buttonKey(messageId: Int, button: BotButton) = "$messageId:${button.row}:${button.column}"

/**
 * Drives any interactive chat/bot flow: resolve a username, load history, listen live, send text,
 * press inline / reply keyboard buttons, and download or stream videos.
 *
 * All state lives in one [MutableStateFlow] updated with atomic [update] calls, so concurrent
 * coroutines (listener, sends, downloads) never lose each other's changes.
 */
class BotChatViewModel @JvmOverloads constructor(
    private val repository: BotChatRepository = BotChatRepository(),
    private val playback: BotVideoPlayback = BotVideoPlayback(repository.account),
) : ViewModel() {

    private val _state = MutableStateFlow(BotChatUiState())
    val state: StateFlow<BotChatUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BotUiEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<BotUiEvent> = _events.asSharedFlow()

    private var sessionJob: Job? = null
    private val downloadJobs = HashMap<Int, Job>()

    /** Last-resort guard: anything unexpected becomes an error event instead of a crash. */
    private val guard = CoroutineExceptionHandler { _, t -> report(t) }

    // ---- chat lifecycle ------------------------------------------------------------------------

    /** Opens (or switches to) a chat by public username. Cancels the previous chat's listener. */
    fun open(target: String) {
        sessionJob?.cancel()
        cancelAllDownloadJobs()
        _state.value = BotChatUiState(target = target.trim(), status = BotChatUiState.Status.RESOLVING)

        sessionJob = viewModelScope.launch(guard) {
            val chat = try {
                repository.resolve(target)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                fail(t)
                return@launch
            }
            _state.update { it.copy(chat = chat, status = BotChatUiState.Status.LOADING_HISTORY) }

            // Start listening before loading history so nothing that arrives in between is lost.
            launch {
                try {
                    repository.updates(chat.chatId).collect { reduce(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    report(t)
                }
            }

            try {
                val history = repository.loadRecent(chat.chatId, HISTORY_PAGE)
                _state.update { s ->
                    val merged = merge(s.messages, history)
                    s.copy(
                        messages = merged,
                        replyKeyboard = latestReplyKeyboard(merged),
                        needsStart = chat.isBot && merged.isEmpty(),
                        status = BotChatUiState.Status.READY,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // History is optional: the chat still works live.
                _state.update { it.copy(status = BotChatUiState.Status.READY, needsStart = chat.isBot) }
                report(t)
            }
        }
    }

    /** Re-opens the current target, e.g. after a network error. */
    fun retry() {
        val target = _state.value.target
        if (target.isNotEmpty()) open(target)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---- sending -------------------------------------------------------------------------------

    fun send(text: String) {
        val chat = _state.value.chat ?: return emit(BotUiEvent.Error(BotError.NoChat))
        if (text.isBlank()) return
        viewModelScope.launch(guard) {
            runReporting { repository.sendText(chat.chatId, text) }
            _state.update { it.copy(needsStart = false) }
        }
    }

    fun startBot(startParameter: String? = null) {
        val chat = _state.value.chat ?: return emit(BotUiEvent.Error(BotError.NoChat))
        _state.update { it.copy(needsStart = false) }
        viewModelScope.launch(guard) { runReporting { repository.startBot(chat.chatId, startParameter) } }
    }

    /** Reply-keyboard key. Plain keys send their text; special keys need the official UI flows. */
    fun onReplyButton(button: ReplyButton) {
        when (button.kind) {
            ReplyButton.Kind.TEXT -> send(button.text)
            else -> emit(BotUiEvent.Toast("This button needs the full chat screen"))
        }
        if (_state.value.replyKeyboard?.singleUse == true) _state.update { it.copy(replyKeyboard = null) }
    }

    // ---- inline buttons ------------------------------------------------------------------------

    fun onInlineButton(message: BotMessage, button: BotButton) {
        when (button) {
            is BotButton.Callback, is BotButton.Game -> pressCallback(message, button)
            is BotButton.Url -> emit(BotUiEvent.OpenUrl(button.url))
            is BotButton.WebApp -> emit(BotUiEvent.OpenWebApp(button.url, isLogin = false))
            is BotButton.LoginUrl -> emit(BotUiEvent.OpenWebApp(button.url, isLogin = true))
            is BotButton.Copy -> emit(BotUiEvent.CopyText(button.copyText))
            is BotButton.UserProfile -> emit(BotUiEvent.OpenProfile(button.userId))
            is BotButton.SwitchInline -> {
                val username = _state.value.chat?.username ?: return
                emit(BotUiEvent.SwitchInline(username, button.query, button.samePeer))
            }
            is BotButton.Buy -> emit(BotUiEvent.Toast("Payments open in the full chat screen"))
            is BotButton.Disabled -> Unit
            is BotButton.Unsupported -> emit(BotUiEvent.Toast("Unsupported button"))
        }
    }

    private fun pressCallback(message: BotMessage, button: BotButton) {
        val key = buttonKey(message.id, button)
        var alreadyPending = false
        _state.update {
            alreadyPending = key in it.pendingButtons
            if (alreadyPending) it else it.copy(pendingButtons = it.pendingButtons + key)
        }
        if (alreadyPending) return // ignore double taps while the bot is thinking

        viewModelScope.launch(guard) {
            try {
                val answer = repository.pressCallbackButton(message, button)
                when {
                    answer.url != null -> emit(BotUiEvent.OpenUrl(answer.url))
                    answer.message != null && answer.alert -> emit(BotUiEvent.Alert(answer.message))
                    answer.message != null -> emit(BotUiEvent.Toast(answer.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BotException) {
                // Bots that answer late still apply the action (edit/new message arrives via updates).
                if (e.error !is BotError.BotTimeout) report(e)
            } catch (t: Throwable) {
                report(t)
            } finally {
                _state.update { it.copy(pendingButtons = it.pendingButtons - key) }
            }
        }
    }

    // ---- video ---------------------------------------------------------------------------------

    /** Streams immediately (tg://) or plays the downloaded file. */
    fun play(message: BotMessage) {
        val item = playback.mediaItem(message)
            ?: return emit(BotUiEvent.Error(BotError.Download("message has no video")))
        emit(BotUiEvent.Play(message, item))
    }

    @JvmOverloads
    fun download(message: BotMessage, priority: Int = FileLoader.PRIORITY_HIGH) {
        if (message.video == null) return
        synchronized(downloadJobs) { if (downloadJobs[message.id]?.isActive == true) return }

        // Lazy start: register the job before it can finish (an already-downloaded file completes instantly).
        val job = viewModelScope.launch(guard, start = CoroutineStart.LAZY) {
            try {
                playback.download(message, priority).collect { state ->
                    _state.update { it.copy(downloads = it.downloads + (message.id to state)) }
                    if (state is DownloadState.Failed) report(BotException(state.error))
                }
            } finally {
                synchronized(downloadJobs) {
                    if (downloadJobs[message.id] === coroutineContext[Job]) downloadJobs.remove(message.id)
                }
            }
        }
        synchronized(downloadJobs) { downloadJobs[message.id] = job }
        job.start()
    }

    fun cancelDownload(message: BotMessage) {
        synchronized(downloadJobs) { downloadJobs.remove(message.id) }?.cancel()
        playback.cancelDownload(message)
        _state.update { it.copy(downloads = it.downloads - message.id) }
    }

    /** Local path of a finished download, or null. */
    fun localPath(message: BotMessage): String? =
        (_state.value.downloads[message.id] as? DownloadState.Completed)?.localPath
            ?: playback.localFile(message)?.absolutePath

    // ---- update reducer ------------------------------------------------------------------------

    private fun reduce(update: BotUpdate) {
        if (update is BotUpdate.SendFailed) {
            // Side effects stay outside update {}: its lambda can run more than once.
            if (_state.value.messages.any { it.id == update.messageId }) {
                emit(BotUiEvent.Error(BotError.Network("message not sent")))
            }
            return
        }
        _state.update { s ->
            val messages = when (update) {
                is BotUpdate.NewMessages -> merge(s.messages, update.messages)
                is BotUpdate.EditedMessages -> {
                    val edits = update.messages.associateBy { it.id }
                    s.messages.map { edits[it.id] ?: it }
                }
                is BotUpdate.DeletedMessages -> {
                    val gone = update.ids.toHashSet()
                    s.messages.filterNot { it.id in gone }
                }
                is BotUpdate.MessageIdChanged ->
                    merge(s.messages.filterNot { it.id == update.oldId }, listOf(update.newMessage))
                is BotUpdate.SendFailed -> s.messages // handled above
            }
            val newestReply = (update as? BotUpdate.NewMessages)?.messages?.any { !it.isOutgoing } == true
            s.copy(
                messages = messages,
                replyKeyboard = if (newestReply || update is BotUpdate.DeletedMessages) latestReplyKeyboard(messages) else s.replyKeyboard,
                needsStart = s.needsStart && messages.isEmpty(),
            )
        }
    }

    private fun merge(current: List<BotMessage>, incoming: List<BotMessage>): List<BotMessage> {
        if (incoming.isEmpty()) return current
        val byId = LinkedHashMap<Int, BotMessage>(current.size + incoming.size)
        current.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        // Temporary (negative) ids are still sending: keep them after the confirmed ones.
        return byId.values
            .sortedWith(compareBy<BotMessage>({ it.id < 0 }, { if (it.id < 0) -it.id else it.id }))
            .takeLast(MAX_MESSAGES)
    }

    /** The reply keyboard set by the bot's most recent message that touched it. */
    private fun latestReplyKeyboard(messages: List<BotMessage>): ReplyKeyboard? {
        for (m in messages.asReversed()) {
            if (m.isOutgoing) continue
            if (m.hidesReplyKeyboard) return null
            m.replyKeyboard?.let { return it }
        }
        return null
    }

    // ---- errors --------------------------------------------------------------------------------

    private suspend fun runReporting(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            report(t)
        }
    }

    private fun fail(t: Throwable) {
        val error = t.toBotError()
        _state.update { it.copy(status = BotChatUiState.Status.FAILED, error = error) }
        emit(BotUiEvent.Error(error))
    }

    private fun report(t: Throwable) {
        if (t is CancellationException) return
        val error = t.toBotError()
        _state.update { it.copy(error = error) }
        emit(BotUiEvent.Error(error))
    }

    private fun Throwable.toBotError(): BotError =
        (this as? BotException)?.error ?: BotError.Unexpected(message ?: javaClass.simpleName)

    private fun emit(event: BotUiEvent) {
        _events.tryEmit(event)
    }

    private fun cancelAllDownloadJobs() {
        val jobs = synchronized(downloadJobs) { downloadJobs.values.toList().also { downloadJobs.clear() } }
        jobs.forEach { it.cancel() } // engine downloads keep running; only listening stops
    }

    override fun onCleared() {
        sessionJob?.cancel()
        cancelAllDownloadJobs()
    }

    // ---- wiring --------------------------------------------------------------------------------

    class Factory(private val account: Int = UserConfig.selectedAccount) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BotChatViewModel::class.java)) { "Unknown ViewModel $modelClass" }
            val repository = BotChatRepository(account)
            return BotChatViewModel(repository, BotVideoPlayback(account)) as T
        }
    }

    /**
     * Vibgram screens are BaseFragments, not AndroidX fragments, so they have no ViewModelStore.
     * Hold one of these in the fragment: create in onFragmentCreate, call [clear] in onFragmentDestroy.
     */
    class Holder @JvmOverloads constructor(account: Int = UserConfig.selectedAccount) {
        private val store = ViewModelStore()
        val viewModel: BotChatViewModel = ViewModelProvider(store, Factory(account))[BotChatViewModel::class.java]
        fun clear() = store.clear()
    }

    companion object {
        const val MAX_MESSAGES = 300
        private const val HISTORY_PAGE = 50
    }
}
