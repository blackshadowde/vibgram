package org.telegram.messenger.vibgram.bot

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.TLObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * Coroutine bridges over the engine's callback APIs.
 *
 * Threading rules of the engine this file encodes:
 *  - ConnectionsManager callbacks arrive on the network thread.
 *  - NotificationCenter observers must be added/removed on the UI thread, and events are
 *    delivered there. Engine objects inside event args (MessageObject lists…) are owned by the
 *    UI thread, so they are mapped to immutable models inside the callback, before leaving it.
 *  - MessagesController.putUsers/putChats and SendMessagesHelper.sendMessage run on the UI thread.
 */

internal const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

/**
 * Sends [request] and suspends until the server answers.
 * Throws [BotException] for server errors and timeouts; cancelling the coroutine cancels the request.
 */
internal suspend fun ConnectionsManager.await(
    request: TLObject,
    timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
): TLObject = try {
    withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val token = sendRequest(request, RequestDelegate { response, error ->
                if (!cont.isActive) return@RequestDelegate
                when {
                    error != null -> cont.resumeWithException(BotException(BotError.from(error)))
                    response == null -> cont.resumeWithException(BotException(BotError.Unexpected("empty response")))
                    else -> cont.resume(response)
                }
            })
            cont.invokeOnCancellation { cancelRequest(token, true) }
        }
    }
} catch (e: TimeoutCancellationException) {
    throw BotException(BotError.Network("request timed out"))
}

/** Same as [await] but casts the response, failing cleanly on an unexpected type. */
internal suspend inline fun <reified T : TLObject> ConnectionsManager.awaitTyped(
    request: TLObject,
    timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
): T {
    val response = await(request, timeoutMs)
    return response as? T
        ?: throw BotException(BotError.Unexpected("unexpected response ${response.javaClass.simpleName}"))
}

/** Runs [block] on the UI thread (immediately if already there). */
internal suspend fun <T> onUi(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

/**
 * Observes NotificationCenter [ids] as a Flow.
 *
 * [map] runs on the UI thread inside the observer callback; return null to drop an event.
 * Registration happens on the UI thread and is undone when collection stops. Unbounded buffer, so
 * a slow collector never makes the engine drop or block events.
 */
internal fun <T : Any> NotificationCenter.observe(
    vararg ids: Int,
    map: (id: Int, args: Array<out Any?>) -> T?,
): Flow<T> = callbackFlow {
    val observer = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            val value = try {
                map(id, args)
            } catch (t: Throwable) {
                // A malformed event must never take the listener down.
                null
            }
            if (value != null) trySend(value)
        }
    }
    ids.forEach { addObserver(observer, it) }
    awaitClose {
        val remove = Runnable { ids.forEach { removeObserver(observer, it) } }
        if (Looper.myLooper() == Looper.getMainLooper()) remove.run() else AndroidUtilities.runOnUIThread(remove)
    }
}
    .buffer(Channel.UNLIMITED)
    .flowOn(Dispatchers.Main.immediate)
