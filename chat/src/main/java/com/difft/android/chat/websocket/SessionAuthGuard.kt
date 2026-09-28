package com.difft.android.chat.websocket

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.session.AuthFailureSink
import com.difft.android.base.session.AuthFailureSource
import com.difft.android.base.session.InactiveDeviceNotifier
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.chat.di.AppCoroutineScope
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped owner of the "this device's credential was rejected by the server" decision.
 *
 * Signals arrive from every Basic-auth channel: the WS handshake (AUTHENTICATION_FAILED), in-socket
 * responses and REST calls (both via [AuthFailureSink]). A signal is never acted on directly — it
 * schedules a single confirming device-list call; only a 401/403 there means the session is gone.
 * A 2xx clears it (false alarm), and a network error leaves the guard armed for the next signal
 * (the health monitor's own backoff re-emits AUTHENTICATION_FAILED within one cycle). A signal is
 * never discarded because the socket reconnected — only [stop] drops the queue.
 *
 * Confirms are serialised through a conflated channel and separated by [MIN_CONFIRM_INTERVAL_MS],
 * so the monitor's reconnect loop cannot turn into a confirm storm.
 *
 * A confirm that itself returns 401 re-enters [onAuthRejected] through DeviceRepository's hook: the
 * self-signal lands in the channel first, then the exception reaches [confirm]'s catch, which sets
 * the [loggedOut] latch. The consumer's next iteration receives that queued signal, sees the latch,
 * and returns without confirming. No recursion — do not "fix" this with an extra flag on the call.
 *
 * The socket state is a replay-1 SharedFlow, so [start] can be handed a stale terminal state. At
 * worst that costs one confirm, which answers 2xx and is discarded.
 *
 * Lifetime is bound to [WebSocketManager.start] / [WebSocketManager.stop].
 */
@Singleton
class SessionAuthGuard @Inject constructor(
    private val appWebSocketHelper: AppWebSocketHelper,
    private val deviceRepository: DeviceRepository,
    // Lazy breaks a Dagger cycle: WebSocketManager -> this guard -> LogoutManager ->
    // WebSocketManager. The logout manager is only needed at the moment a kick is confirmed.
    private val logoutManager: dagger.Lazy<LogoutManager>,
    private val userManager: UserManager,
    @param:AppCoroutineScope private val scope: CoroutineScope,
) : AuthFailureSink {

    // CONFLATED: never suspends the producer and never fails trySend, so onAuthRejected() honours
    // its "any thread, never throws" contract; signals arriving during a confirm collapse to one.
    private val signals = Channel<String>(Channel.CONFLATED)

    // Terminal once set: the logout sequence ends in process death, so a second one is always a bug.
    private val loggedOut = AtomicBoolean(false)

    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) {
            L.w { "[SessionAuthGuard] already started, ignoring duplicate call" }
            return
        }
        L.i { "[SessionAuthGuard] start" }
        job = scope.launch {
            launch { collectSocketState() }
            launch { consumeSignals() }
        }
    }

    @Synchronized
    fun stop() {
        L.i { "[SessionAuthGuard] stop" }
        job?.cancel()
        job = null
        drainSignals()
    }

    /** [AuthFailureSink]: fire-and-forget, any thread, never throws. */
    override fun onAuthRejected(source: String) {
        if (loggedOut.get()) return
        signals.trySend(source)
    }

    private suspend fun collectSocketState() {
        appWebSocketHelper.chatDataWebSocketConnection.webSocketConnectionState.collect { state ->
            when (state) {
                WebSocketConnectionState.AUTHENTICATION_FAILED ->
                    onAuthRejected(AuthFailureSource.WS_HANDSHAKE)

                WebSocketConnectionState.INACTIVE_FAILED -> InactiveDeviceNotifier.raise()

                // A healthy socket says nothing about a 401 another Basic-auth channel just saw, and
                // dropping the queue here would race trySend from an OkHttp thread. Queued signals
                // are left to confirm; a stale one costs one call, answered 2xx.
                WebSocketConnectionState.CONNECTED -> InactiveDeviceNotifier.clear()

                // Connectivity states are the health monitor's business, not ours.
                WebSocketConnectionState.CONNECTING,
                WebSocketConnectionState.DISCONNECTED,
                WebSocketConnectionState.FAILED,
                WebSocketConnectionState.UNKNOWN_HOST_FAILED -> Unit
            }
        }
    }

    private suspend fun consumeSignals() {
        for (source in signals) {
            if (loggedOut.get()) return
            confirm(source)
            delay(MIN_CONFIRM_INTERVAL_MS)
        }
    }

    /** The device call is the tie-breaker: 401/403 = kicked, 2xx = false alarm, error = unknown. */
    private suspend fun confirm(source: String) {
        if (userManager.getUserData()?.baseAuth.isNullOrEmpty()) {
            L.i { "[SessionAuthGuard] confirm skipped source=$source reason=noCredential" }
            return
        }
        L.i { "[SessionAuthGuard] confirming source=$source" }
        try {
            deviceRepository.checkDeviceAuth()
            L.i { "[SessionAuthGuard] confirm: credential still valid, false alarm source=$source" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthorizationFailedException) {
            if (!loggedOut.compareAndSet(false, true)) return
            L.w { "[SessionAuthGuard] kick-out confirmed code=${e.code} source=$source, logging out" }
            logoutManager.get().doLogoutWithoutRemoveData()
        } catch (e: Exception) {
            L.w { "[SessionAuthGuard] confirm inconclusive source=$source: ${e.stackTraceToString()}" }
        }
    }

    private fun drainSignals() {
        while (signals.tryReceive().isSuccess) Unit
    }

    private companion object {
        // Desktop's rate limit for the same decision: at most one confirming call per 5 s no
        // matter how fast the reconnect loop re-emits AUTHENTICATION_FAILED.
        const val MIN_CONFIRM_INTERVAL_MS = 5_000L
    }
}
