package org.telegram.messenger.vibgram.bot

import kotlinx.coroutines.flow.Flow
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

/**
 * General-purpose bot / chat pipeline on top of Vibgram's own Telegram engine (tgnet).
 * Uses the logged-in account, so everything stays in sync with the normal chat list.
 *
 * Thread-safe: every public function can be called from any coroutine; engine calls that must
 * run on the UI thread are dispatched there internally.
 */
class BotChatRepository @JvmOverloads constructor(
    val account: Int = UserConfig.selectedAccount,
) {
    private val connections: ConnectionsManager get() = ConnectionsManager.getInstance(account)
    private val messages: MessagesController get() = MessagesController.getInstance(account)

    // ---- chat resolution -----------------------------------------------------------------------

    /**
     * Resolves a public username to its chat. Accepts "name", "@name", "t.me/name",
     * "https://t.me/name?start=x". Uses the local cache first, then contacts.resolveUsername.
     */
    suspend fun resolve(target: String): BotChat {
        val username = normalizeUsername(target)
            ?: throw BotException(BotError.UsernameNotFound(target.trim()))

        onUi { messages.getUserOrChat(username) }?.let { cached ->
            toBotChat(cached, username)?.let { return it }
        }

        val request = TLRPC.TL_contacts_resolveUsername().apply { this.username = username }
        val resolved = try {
            connections.awaitTyped<TLRPC.TL_contacts_resolvedPeer>(request)
        } catch (e: BotException) {
            if (e.error is BotError.UsernameNotFound) throw BotException(BotError.UsernameNotFound(username))
            throw e
        }

        onUi {
            messages.putUsers(resolved.users, false)
            messages.putChats(resolved.chats, false)
        }
        MessagesStorage.getInstance(account).putUsersAndChats(resolved.users, resolved.chats, false, true)

        val chatId = DialogObject.getPeerDialogId(resolved.peer)
        val entity: TLObject? = if (chatId > 0) {
            resolved.users.firstOrNull { it.id == chatId }
        } else {
            resolved.chats.firstOrNull { it.id == -chatId }
        }
        return entity?.let { toBotChat(it, username) }
            ?: throw BotException(BotError.UsernameNotFound(username))
    }

    // ---- sending -------------------------------------------------------------------------------

    /**
     * Sends a plain text message (also how reply-keyboard keys and /commands are "pressed").
     * Returns once the message is queued; delivery shows up in [updates] (temporary id ->
     * [BotUpdate.MessageIdChanged], or [BotUpdate.SendFailed]). Long text is split at 4096 chars.
     */
    suspend fun sendText(chatId: Long, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        requireKnownPeer(chatId)
        onUi {
            val helper = SendMessagesHelper.getInstance(account)
            body.chunked(MAX_MESSAGE_LENGTH).forEach { part ->
                helper.sendMessage(SendMessagesHelper.SendMessageParams.of(part, chatId))
            }
        }
    }

    /** Starts a bot the same way the official "Start" button does (deep-link payload optional). */
    suspend fun startBot(chatId: Long, startParameter: String? = null) {
        sendText(chatId, if (startParameter.isNullOrBlank()) "/start" else "/start $startParameter")
    }

    // ---- inline buttons ------------------------------------------------------------------------

    /**
     * Presses a callback (or game) button and waits for the bot's answer.
     * The bot usually reacts by editing the message or sending a new one — both arrive via [updates].
     */
    suspend fun pressCallbackButton(message: BotMessage, button: BotButton): BotCallbackAnswer {
        if (message.id <= 0) throw BotException(BotError.ButtonExpired)
        val request = TLRPC.TL_messages_getBotCallbackAnswer()
        when (button) {
            is BotButton.Callback -> {
                if (button.requiresPassword) throw BotException(BotError.PasswordRequired)
                request.data = button.data
                request.flags = request.flags or FLAG_DATA
            }
            is BotButton.Game -> request.game = true
            else -> throw IllegalArgumentException("Not a callback button: ${button::class.simpleName}")
        }
        request.peer = onUi { messages.getInputPeer(message.chatId) }
            ?: throw BotException(BotError.ChatUnavailable("unknown peer"))
        request.msg_id = message.id

        val answer = connections.awaitTyped<TLRPC.TL_messages_botCallbackAnswer>(request, CALLBACK_TIMEOUT_MS)
        return BotCallbackAnswer(
            message = answer.message?.takeIf { it.isNotBlank() },
            alert = answer.alert,
            url = answer.url?.takeIf { it.isNotBlank() },
        )
    }

    // ---- history + live updates ----------------------------------------------------------------

    /** Latest [limit] messages of the chat (newest first from the server; returned oldest first). */
    suspend fun loadRecent(chatId: Long, limit: Int = 50): List<BotMessage> {
        val request = TLRPC.TL_messages_getHistory().apply {
            peer = onUi { messages.getInputPeer(chatId) } ?: throw BotException(BotError.ChatUnavailable("unknown peer"))
            this.limit = limit.coerceIn(1, 100)
        }
        val result = connections.awaitTyped<TLRPC.messages_Messages>(request)
        MessagesStorage.getInstance(account).putUsersAndChats(result.users, result.chats, false, true)
        return onUi {
            messages.putUsers(result.users, false)
            messages.putChats(result.chats, false)
            result.messages
                .filter { it !is TLRPC.TL_messageEmpty }
                .map { BotMessageParser.parse(MessageObject(account, it, false, true)) }
                .sortedBy { it.id }
        }
    }

    /**
     * Live stream of everything that happens in [chatId]: new messages (bot replies and our own
     * sends), edits (bots swapping keyboards / pages), deletions and send acknowledgements.
     * Cold flow: listening starts on collect and stops when the collector is cancelled.
     */
    fun updates(chatId: Long): Flow<BotUpdate> {
        val isChannel = chatId < 0 && ChatObject.isChannel(messages.getChat(-chatId))
        return NotificationCenter.getInstance(account).observe(
            NotificationCenter.didReceiveNewMessages,
            NotificationCenter.replaceMessagesObjects,
            NotificationCenter.messagesDeleted,
            NotificationCenter.messageReceivedByServer,
            NotificationCenter.messageSendError,
        ) { id, args ->
            when (id) {
                NotificationCenter.didReceiveNewMessages -> {
                    if ((args[0] as Number).toLong() != chatId) return@observe null
                    if (args.getOrNull(2) == true) return@observe null // scheduled
                    if ((args.getOrNull(3) as? Number)?.toInt()?.let { it != 0 } == true) return@observe null // quick replies / saved
                    val parsed = messageObjects(args[1]).map(BotMessageParser::parse)
                    if (parsed.isEmpty()) null else BotUpdate.NewMessages(parsed)
                }
                NotificationCenter.replaceMessagesObjects -> {
                    if ((args[0] as Number).toLong() != chatId) return@observe null
                    val parsed = messageObjects(args[1]).map(BotMessageParser::parse)
                    if (parsed.isEmpty()) null else BotUpdate.EditedMessages(parsed)
                }
                NotificationCenter.messagesDeleted -> {
                    if (args.getOrNull(2) == true) return@observe null // scheduled
                    val channelId = (args[1] as Number).toLong()
                    // Channel ids are per-channel; everything else shares account-wide ids.
                    if (if (isChannel) channelId != -chatId else channelId != 0L) return@observe null
                    val ids = (args[0] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty()
                    if (ids.isEmpty()) null else BotUpdate.DeletedMessages(ids)
                }
                NotificationCenter.messageReceivedByServer -> {
                    if ((args[3] as Number).toLong() != chatId) return@observe null
                    if (args.getOrNull(6) == true) return@observe null // scheduled
                    val oldId = (args[0] as Number).toInt()
                    val message = args[2] as? TLRPC.Message ?: return@observe null
                    BotUpdate.MessageIdChanged(oldId, BotMessageParser.parse(MessageObject(account, message, false, false)))
                }
                NotificationCenter.messageSendError -> BotUpdate.SendFailed((args[0] as Number).toInt())
                else -> null
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private suspend fun requireKnownPeer(chatId: Long) {
        val known = onUi { if (chatId > 0) messages.getUser(chatId) != null else messages.getChat(-chatId) != null }
        if (!known) throw BotException(BotError.ChatUnavailable("chat not resolved"))
    }

    private fun messageObjects(arg: Any?): List<MessageObject> =
        (arg as? List<*>)?.filterIsInstance<MessageObject>().orEmpty()

    private fun toBotChat(entity: TLObject, username: String): BotChat? = when (entity) {
        is TLRPC.User -> BotChat(
            chatId = entity.id,
            username = entity.username ?: username,
            title = UserObject.getUserName(entity),
            isBot = entity.bot,
            isChannel = false,
        )
        is TLRPC.Chat -> BotChat(
            chatId = -entity.id,
            username = entity.username ?: username,
            title = entity.title.orEmpty(),
            isBot = false,
            isChannel = ChatObject.isChannelAndNotMegaGroup(entity),
        )
        else -> null
    }

    companion object {
        private const val MAX_MESSAGE_LENGTH = 4096
        private const val FLAG_DATA = 1 // messages.getBotCallbackAnswer flags.0: data present
        /** Bots get ~15 s server-side; leave room for the round trip on slow links. */
        private const val CALLBACK_TIMEOUT_MS = 45_000L

        private val USERNAME = Regex("^[A-Za-z][A-Za-z0-9_]{3,31}$")

        /** "@Name", "t.me/Name", "https://t.me/Name/12?start=x", "tg://resolve?domain=Name" -> "Name". */
        @JvmStatic
        fun normalizeUsername(input: String): String? {
            var s = input.trim()
            if (s.startsWith("tg://resolve", ignoreCase = true) || s.startsWith("tg:resolve", ignoreCase = true)) {
                s = s.substringAfter("domain=", "").substringBefore('&')
            } else {
                s = s.removePrefix("https://").removePrefix("http://")
                listOf("www.t.me/", "t.me/", "telegram.me/", "telegram.dog/").firstOrNull { s.startsWith(it, ignoreCase = true) }
                    ?.let { s = s.substring(it.length) }
                s = s.removePrefix("@").substringBefore('/').substringBefore('?').substringBefore('#')
            }
            return s.takeIf { USERNAME.matches(it) }
        }
    }
}
