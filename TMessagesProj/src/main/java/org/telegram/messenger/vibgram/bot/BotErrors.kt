package org.telegram.messenger.vibgram.bot

import org.telegram.tgnet.TLRPC

/** Every failure the bot pipeline can surface to the UI. */
sealed class BotError {
    /** No public chat/bot with that username. */
    data class UsernameNotFound(val username: String) : BotError()
    /** Telegram asked us to slow down. Retry after [seconds]. */
    data class FloodWait(val seconds: Int) : BotError()
    /** The bot didn't answer the button in time (BOT_RESPONSE_TIMEOUT). Usually harmless. */
    object BotTimeout : BotError()
    /** The button belongs to a message that is gone or was edited (MESSAGE_ID_INVALID / DATA_INVALID). */
    object ButtonExpired : BotError()
    /** Callback button needs the account 2FA password (bot payments / ownership transfer). Not supported here. */
    object PasswordRequired : BotError()
    /** The chat can't be written to / addressed (PEER_ID_INVALID, USER_IS_BLOCKED, CHAT_WRITE_FORBIDDEN…). */
    data class ChatUnavailable(val reason: String) : BotError()
    /** No connection, or the request didn't complete before the timeout. */
    data class Network(val reason: String) : BotError()
    /** File download failed or was cancelled. */
    data class Download(val reason: String) : BotError()
    /** No chat has been opened yet. */
    object NoChat : BotError()
    /** Any other server error. */
    data class Server(val code: Int, val text: String) : BotError()
    data class Unexpected(val message: String) : BotError()

    companion object {
        fun from(error: TLRPC.TL_error?): BotError {
            if (error == null) return Unexpected("empty error")
            val text = error.text ?: ""
            return when {
                text.startsWith("FLOOD_WAIT_") || text.startsWith("FLOOD_PREMIUM_WAIT_") ->
                    FloodWait(text.substringAfterLast('_').toIntOrNull() ?: 30)
                text == "USERNAME_NOT_OCCUPIED" || text == "USERNAME_INVALID" ->
                    UsernameNotFound("")
                text == "BOT_RESPONSE_TIMEOUT" -> BotTimeout
                text == "MESSAGE_ID_INVALID" || text == "DATA_INVALID" -> ButtonExpired
                text == "PASSWORD_HASH_INVALID" || text == "PASSWORD_MISSING" -> PasswordRequired
                text == "PEER_ID_INVALID" || text == "USER_IS_BLOCKED" || text == "CHAT_WRITE_FORBIDDEN" ||
                    text == "USER_BANNED_IN_CHANNEL" || text == "INPUT_USER_DEACTIVATED" || text == "CHANNEL_PRIVATE" ->
                    ChatUnavailable(text)
                // Engine-side failures (no connection, request dropped) come back with negative codes.
                error.code < 0 || text.contains("TIMEOUT") || text.contains("NETWORK") ->
                    Network(text.ifEmpty { "code ${error.code}" })
                else -> Server(error.code, text)
            }
        }
    }
}

/** Exception wrapper so suspend functions can fail with a typed [BotError]. */
class BotException(val error: BotError) : Exception(error.toString())
