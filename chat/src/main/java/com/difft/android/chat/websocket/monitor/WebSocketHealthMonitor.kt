package com.difft.android.chat.websocket.monitor

import com.difft.android.base.utils.globalServices

import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.network.UrlManager
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import com.difft.android.websocket.api.websocket.HealthMonitor
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import com.difft.android.websocket.internal.websocket.WebSocketConnection
import com.difft.android.websocket.internal.websocket.value
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.difft.android.chat.jobmanager.impl.BackoffUtil
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import util.AppForegroundObserver
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What the monitor should do about a state, split out so the branch matrix is unit-testable. */
internal enum class ReconnectPolicy { RECONNECT_SWITCH_HOST, RECONNECT_KEEP_HOST, CONNECTED, IGNORE }

/**
 * Why a monitor wait ended before its uptime timeout. [KEEP_ALIVE_ALARM] is the routine
 * background cadence (see [KeepAliveWakeAlarm]); [CONNECTED] comes from the connection state
 * and only re-enters the wait so the wake alarm covers the new socket; the rest are genuine
 * external events.
 */
internal enum class WakeReason { SERVICE_ALARM, NETWORK, FOREGROUND, KEEP_ALIVE_ALARM, CONNECTED }

private const val RECONNECT_BACKOFF_CAP_FOREGROUND_MS = 5_000L
private const val RECONNECT_BACKOFF_CAP_BACKGROUND_MS = 30_000L

/**
 * Cap for the exponential reconnect backoff. Foreground keeps 5 s: someone is watching the
 * connection state, and iOS / Desktop retry on a fixed 5 s timer. Background uses
 * Signal-Android's 30 s cap, so a network that is present but unusable (Doze restriction,
 * dead Wi-Fi) costs a handful of attempts per 5-minute alarm period instead of one every ~15 s.
 * Any wake event still cuts the wait short and the attempt count resets on CONNECTED.
 */
internal fun reconnectBackoffCapMs(isForegrounded: Boolean): Long =
    if (isForegrounded) RECONNECT_BACKOFF_CAP_FOREGROUND_MS else RECONNECT_BACKOFF_CAP_BACKGROUND_MS

/**
 * Auth-level rejections keep the host: the server answered correctly, it rejected the *credential*.
 * Recording the host as failed would pollute host selection, and reporting it as a WS failure would
 * feed a network-quality counter that triggers a speed test. Reconnecting is still right — a false
 * alarm self-heals into CONNECTED, and a real kick keeps re-emitting until the guard confirms it.
 */
internal fun reconnectPolicyFor(state: WebSocketConnectionState): ReconnectPolicy = when (state) {
    WebSocketConnectionState.AUTHENTICATION_FAILED,
    WebSocketConnectionState.INACTIVE_FAILED -> ReconnectPolicy.RECONNECT_KEEP_HOST

    WebSocketConnectionState.FAILED,
    WebSocketConnectionState.DISCONNECTED,
    WebSocketConnectionState.UNKNOWN_HOST_FAILED -> ReconnectPolicy.RECONNECT_SWITCH_HOST

    WebSocketConnectionState.CONNECTED -> ReconnectPolicy.CONNECTED

    WebSocketConnectionState.CONNECTING -> ReconnectPolicy.IGNORE
}

/**
 * The monitor is also responsible for sending heartbeats/keep-alive messages to prevent
 * timeouts, if timeout then disconnect the websocket connection. and then the connection will be reconnected inside [checkUnconnectedStateAndReconnect].
 *
 * The monitor lifecycle is same to the application, it will only be destroyed when the application is destroyed.
 */
@Singleton
class WebSocketHealthMonitor
@Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val urlManager: UrlManager,
    private val coordinator: DomainSpeedTestCoordinator,
    private val keepAliveWakeAlarm: KeepAliveWakeAlarm,
) : HealthMonitor, CoroutineScope by appScope {

    @Volatile
    private var lastHeartbeatResponseTime: Long = System.currentTimeMillis()

    // Elapsed-clock twin of lastHeartbeatResponseTime for the stale-probe math: wall clock
    // can step (NTP) right after a Doze wake — exactly when the probe runs — while
    // elapsedRealtime is monotonic across sleep.
    @Volatile
    private var lastHeartbeatResponseElapsed: Long = SystemClock.elapsedRealtime()

    @Volatile
    private var lastHeartbeatSendTime: Long = System.currentTimeMillis()

    /** Count of keep-alives sent; drives the sampled keep-alive log line. */
    @Volatile
    private var keepAliveTicks = 0L

    /**
     * Broadcasts wake events (network change, foreground, service alarm, keep-alive alarm)
     * to every suspended wait point in the monitor. A shared flow drops emissions
     * that find no collector, which is what every wait point wants: a wake is only meaningful
     * while someone is waiting.
     */
    private val notificationFlow = MutableSharedFlow<WakeReason>(
        replay = 0,
        extraBufferCapacity = 1
    )

    private val _monitorWakeTicks = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1
    )

    /**
     * Emits on every health-check loop wake-up. After a vendor freeze this is often the
     * only liveness signal, so subscribers (alarm-chain healing) piggyback on it.
     */
    val monitorWakeTicks: SharedFlow<Unit> = _monitorWakeTicks

    private var staleProbeJob: Job? = null

    @Volatile
    private var attempts = 0

    @Volatile
    private var isMonitoring = false

    private var monitoringJobs = mutableListOf<Job>()

    private var networkMonitor: NetworkMonitor? = null

    // Kept so stopMonitoring() can unregister it.
    private var foregroundListener: AppForegroundObserver.Listener? = null

    private suspend fun waitForNetworkFine(websocketName: String) {
        var waitStart = 0L
        while (!NetworkConstraint.isNetworkAvailable(context)) {
            if (waitStart == 0L) {
                waitStart = SystemClock.elapsedRealtime()
                L.i { "$websocketName [ws]monitor network unavailable, waiting" }
            }
            // Timeout guards against a network change that lands before first() collects.
            try {
                withTimeout(NETWORK_WAIT_TIMEOUT) {
                    notificationFlow.first()
                }
            } catch (_: TimeoutCancellationException) {
                // Re-check the network state.
            }
        }
        if (waitStart != 0L) {
            L.i { "$websocketName [ws]monitor network available after ${SystemClock.elapsedRealtime() - waitStart}ms" }
        }
    }

    override fun onKeepAliveResponse() {
        lastHeartbeatResponseTime = System.currentTimeMillis()
        lastHeartbeatResponseElapsed = SystemClock.elapsedRealtime()
    }

    override fun monitor(webSocketConnection: WebSocketConnection) {
        if (isMonitoring) {
            L.w { "${webSocketConnection.name} [ws]monitor: Already monitoring, ignoring duplicate call" }
            return
        }

        isMonitoring = true
        L.i { "${webSocketConnection.name} [ws]monitor: Starting monitoring" }

        foregroundListener = object : AppForegroundObserver.Listener {
            override fun onForeground() {
                if (isMonitoring) {
                    L.i { "[ws]monitor: App to foreground, notifying all receivers" }
                    notificationFlow.tryEmit(WakeReason.FOREGROUND)
                }
            }
        }
        AppForegroundObserver.addListener(foregroundListener!!)

        networkMonitor = NetworkMonitor(context) {
            if (isMonitoring) {
                L.i { "[ws]monitor: Network changed, notifying all receivers" }
                notificationFlow.tryEmit(WakeReason.NETWORK)
            }
        }.also { it.register() }

        checkConnectedStateHealthy(webSocketConnection)
        checkUnconnectedStateAndReconnect(webSocketConnection)
        handleConnectingTimeout(webSocketConnection)
    }

    override fun stopMonitoring(webSocketConnection: WebSocketConnection?) {
        if (!isMonitoring) {
            L.w { "[ws]monitor: Not monitoring, ignoring stop call" }
            return
        }

        L.i { "[ws]monitor: Stopping monitoring" }
        isMonitoring = false

        monitoringJobs.forEach { it.cancel() }
        monitoringJobs.clear()

        staleProbeJob?.cancel()
        staleProbeJob = null

        keepAliveWakeAlarm.cancel()

        foregroundListener?.let {
            AppForegroundObserver.removeListener(it)
            foregroundListener = null
        }

        networkMonitor?.unregister()
        networkMonitor = null

        webSocketConnection?.let { connection ->
            try {
                L.i { "${connection.name} [ws]monitor: Disconnecting WebSocket connection" }
                connection.disconnectWhenConnected()
            } catch (e: Exception) {
                L.w { "[ws]monitor: Error disconnecting WebSocket: ${e.message}" }
            }
        }
    }

    /**
     * Called on every `ServiceCheckReceiver` alarm. Resets the retry count so the next reconnect
     * skips its backoff, and wakes every waiting point (health check, backoff, network wait).
     * Runs regardless of [isMonitoring]: the alarm exists to keep the background connection
     * alive even when the monitor state is off; `tryEmit` never blocks.
     */
    fun onAlarmTriggered() {
        // The normal case is visible through the externally triggered keep-alive line;
        // an alarm arriving while nothing monitors is the anomaly worth a line.
        if (isMonitoring) L.d { "[ws]monitor: alarm" } else L.w { "[ws]monitor: alarm fired while not monitoring" }
        attempts = 0

        notificationFlow.tryEmit(WakeReason.SERVICE_ALARM)
    }

    private fun handleConnectingTimeout(connection: WebSocketConnection) {
        var timeoutJob: Job? = null
        val job = connection.webSocketConnectionState.onEach { state ->
            if (!isMonitoring) return@onEach

            if (state is WebSocketConnectionState.CONNECTING) {
                timeoutJob?.cancel()
                // Start the timeout coroutine only for CONNECTING state
                timeoutJob = launch {
                    delay(CONNECTING_TIMEOUT)
                    if (isMonitoring) {
                        timeoutJob = null
                        L.i { "${connection.name} [ws]monitor: connecting timeout after ${CONNECTING_TIMEOUT}ms, canceling connection" }
                        connection.cancelConnection()
                    }
                }
                timeoutJob?.let { monitoringJobs.add(it) }
            } else {
                timeoutJob?.let {
                    it.cancel()
                    monitoringJobs.remove(it)
                    timeoutJob = null
                }
            }
        }.launchIn(this)
        monitoringJobs.add(job)
    }

    private fun checkConnectedStateHealthy(webSocketConnection: WebSocketConnection) {
        val job = launch {
            while (isMonitoring) {
                val wakeReason = awaitKeepAliveTick(webSocketConnection)

                if (!isMonitoring) break

                _monitorWakeTicks.tryEmit(Unit)

                // A fresh socket only needs the wait re-entered so the wake alarm covers it;
                // its first keep-alive follows one cadence later as usual.
                if (wakeReason == WakeReason.CONNECTED) continue

                val triggeredByExternalEvent = wakeReason != null && wakeReason != WakeReason.KEEP_ALIVE_ALARM
                if (webSocketConnection.webSocketConnectionState.value == WebSocketConnectionState.CONNECTED) {
                    //why use lastHeartbeatSendTime instead of System.currentTimeMillis()? in case of App may be frozen in background
                    if (lastHeartbeatSendTime - lastHeartbeatResponseTime > MAX_TIME_SINCE_SUCCESSFUL_KEEP_ALIVE) {
                        L.w { "${webSocketConnection.name} [ws]monitor: missed keep-alives, disconnecting lastSend=$lastHeartbeatSendTime lastResponse=$lastHeartbeatResponseTime" }
                        webSocketConnection.disconnectWhenConnected()
                    } else {
                        val sinceLastResponse = SystemClock.elapsedRealtime() - lastHeartbeatResponseElapsed
                        // Out-of-cadence sends (service alarm/network/foreground) are always
                        // logged; the regular timer and wake-alarm cadence is sampled to keep
                        // the file readable. The wake field shows which clock drove the send.
                        val tick = ++keepAliveTicks
                        if (triggeredByExternalEvent || tick % KEEP_ALIVE_LOG_EVERY == 0L) {
                            L.i { "${webSocketConnection.name} [ws]monitor: keep alive n=$tick wake=${wakeReason ?: "timer"} sinceResponse=${sinceLastResponse}ms" }
                        }
                        webSocketConnection.sendKeepAlive()
                        lastHeartbeatSendTime = System.currentTimeMillis()
                        if (wakeReason != null && sinceLastResponse > STALE_PROBE_MIN_SILENCE) {
                            // Any wake (routine alarm included) after a long response silence:
                            // likely a vendor freeze or a late alarm with a possibly-zombie socket;
                            // probe the keep-alive just sent instead of waiting for miss accounting.
                            armStaleProbe(webSocketConnection)
                        }
                    }
                }
            }
        }
        monitoringJobs.add(job)
    }

    /**
     * Waits one keep-alive cadence, or less if a wake event arrives; `null` means the uptime
     * timeout elapsed. While the socket is connected in keep-alive mode an elapsed-realtime
     * alarm is armed as well, because the coroutine timeout runs on the uptime clock and stalls
     * whenever the CPU suspends (issue #1325); whichever fires first ends the wait and the
     * alarm is disarmed.
     */
    private suspend fun awaitKeepAliveTick(webSocketConnection: WebSocketConnection): WakeReason? {
        val stateFlow = webSocketConnection.webSocketConnectionState
        val connected = stateFlow.value == WebSocketConnectionState.CONNECTED
        val armed = connected && keepAliveWakeAlarm.arm { notificationFlow.tryEmit(WakeReason.KEEP_ALIVE_ALARM) }
        // Not connected yet: also end the wait once the socket connects, so the next iteration
        // arms the wake alarm for it. The state flow's replay cache makes this race-free: a
        // CONNECTED that lands before first() subscribes is still observed.
        val wakes = if (connected) {
            notificationFlow
        } else {
            merge(
                notificationFlow,
                stateFlow.filter { it == WebSocketConnectionState.CONNECTED }.map { WakeReason.CONNECTED },
            )
        }
        return try {
            withTimeout(KEEP_ALIVE_SEND_CADENCE) {
                wakes.first()
            }
        } catch (_: TimeoutCancellationException) {
            null
        } finally {
            if (armed) keepAliveWakeAlarm.cancel()
        }
    }

    /**
     * One-shot probe on the keep-alive just sent: no response within [STALE_PROBE_TIMEOUT]
     * → force-disconnect so the reconnect pipeline re-establishes. Single probe in flight;
     * a re-freeze only delays the probe (worst case: one redundant reconnect).
     */
    private fun armStaleProbe(webSocketConnection: WebSocketConnection) {
        if (staleProbeJob?.isActive == true) return
        val probeStartedElapsed = SystemClock.elapsedRealtime()
        L.i { "${webSocketConnection.name} [ws]monitor: arming stale-connection probe (${STALE_PROBE_TIMEOUT}ms)" }
        staleProbeJob = launch {
            // Watch the state instead of a blind delay: if the socket leaves CONNECTED during
            // the window, the probed (zombie) socket is gone and the reconnect pipeline owns
            // recovery — a blind delay would kill the freshly reconnected healthy socket
            // (keep-alive responses are only credited on server frames, never on connect).
            val leftConnected = try {
                withTimeout(STALE_PROBE_TIMEOUT) {
                    webSocketConnection.webSocketConnectionState.first { it != WebSocketConnectionState.CONNECTED }
                }
                true
            } catch (_: TimeoutCancellationException) {
                false
            }
            if (leftConnected) return@launch
            if (!isMonitoring) return@launch
            if (lastHeartbeatResponseElapsed >= probeStartedElapsed) return@launch
            if (webSocketConnection.webSocketConnectionState.value != WebSocketConnectionState.CONNECTED) return@launch
            L.w { "${webSocketConnection.name} [ws]monitor: no keep-alive response within probe window, force reconnecting stale connection" }
            webSocketConnection.disconnectWhenConnected()
        }
    }

    private fun checkUnconnectedStateAndReconnect(webSocketConnection: WebSocketConnection) {
        val job = webSocketConnection.webSocketConnectionState.onEach { state ->
            if (!isMonitoring) return@onEach

            L.i { "${webSocketConnection.name} [ws]monitor: state=$state" }
            when (reconnectPolicyFor(state)) {
                ReconnectPolicy.RECONNECT_SWITCH_HOST -> {
                    coordinator.onWsFailure()
                    // Only a connection-level failure switches host for the next attempt.
                    switchHost(webSocketConnection)
                    handleRedoConnect(webSocketConnection)
                }

                ReconnectPolicy.RECONNECT_KEEP_HOST -> {
                    L.w { "${webSocketConnection.name} [ws]monitor: auth-level failure $state, reconnect without host switch" }
                    handleRedoConnect(webSocketConnection)
                }

                ReconnectPolicy.CONNECTED -> {
                    coordinator.onWsConnected()
                    attempts = 0 // reset attempts when connected
                }

                ReconnectPolicy.IGNORE -> Unit
            }
        }.launchIn(this)
        monitoringJobs.add(job)
    }

    private fun switchHost(connection: WebSocketConnection) {
        L.i { "${connection.name} [ws]monitor: switch host (record current as failed)" }
        urlManager.switchToNextChatWebsocketHost()
    }

    private suspend fun handleRedoConnect(
        webSocketConnection: WebSocketConnection
    ) {
        prepareForConnecting(webSocketConnection)
        withContext(Dispatchers.IO) {
            doConnectInternal(webSocketConnection)
        }
    }

    private suspend fun prepareForConnecting(
        webSocketConnection: WebSocketConnection,
    ) {
        if (didNotLogin()) { //do connect only when current app is login status
            L.i { "${webSocketConnection.name} wait until login status" }
            waitUntilLoginStatus()
            L.i { "${webSocketConnection.name} login status, go on do connect" }
        }
        delayWhenRetry(webSocketConnection)
        waitForNetworkFine(webSocketConnection.name)
    }

    private suspend fun waitUntilLoginStatus() {
        while (didNotLogin()) {
            delay(1000)
        }
    }

    private suspend fun delayWhenRetry(webSocketConnection: WebSocketConnection) {
        if (++attempts > 1) {
            // Exponential backoff (see reconnectBackoffCapMs), cut short by any wake event.
            val backoff = BackoffUtil.exponentialBackoff(attempts, reconnectBackoffCapMs(AppForegroundObserver.isForegrounded()))

            L.w { "${webSocketConnection.name} [ws]monitor: backoff attempts=$attempts delay=${backoff}ms" }

            try {
                withTimeout(backoff) {
                    notificationFlow.first()
                }
                L.i { "${webSocketConnection.name} [ws]monitor: backoff interrupted by external trigger" }
            } catch (_: TimeoutCancellationException) {
                // Backoff elapsed; continue reconnecting.
            }
        }
    }

    private fun didNotLogin(): Boolean {
        val basicAuth = (globalServices.userManager.getUserData()?.baseAuth ?: "")
        return TextUtils.isEmpty(basicAuth)
    }

    companion object {
        // Connect deadline: long enough for a normal handshake (3-5 s), short enough to
        // fail fast and move on to the next host.
        private const val CONNECTING_TIMEOUT = 10_000L

        // Re-check interval while waiting for the network, in case the change notification
        // landed before the wait started.
        private const val NETWORK_WAIT_TIMEOUT = 5_000L

        private val KEEP_ALIVE_SEND_CADENCE =
            TimeUnit.SECONDS.toMillis(WebSocketConnection.KEEP_ALIVE_TIMEOUT_SECONDS.toLong())
        private val MAX_TIME_SINCE_SUCCESSFUL_KEEP_ALIVE = KEEP_ALIVE_SEND_CADENCE * 3

        // Post-wake stale-connection probe deadline: generous for a slow-network round-trip,
        // far shorter than regular keep-alive miss accounting (up to 2 cadences).
        private const val STALE_PROBE_TIMEOUT = 10_000L

        // Minimum response silence before a probe may arm. 2x cadence: a healthy quiet
        // connection sits at ~1 cadence + RTT (phase jitter can cross 1x and cause spurious
        // probes on every 5-min alarm), while a real freeze leaves minutes of silence. The
        // 45 s background cadence (KeepAliveWakeAlarm.CADENCE_MS) + RTT also stays under it;
        // a late alarm crosses it and buys one cheap probe.
        private val STALE_PROBE_MIN_SILENCE = KEEP_ALIVE_SEND_CADENCE * 2

        /** Log every Nth in-cadence keep-alive (20 × 30-45 s = one line per 10-15 min). */
        private const val KEEP_ALIVE_LOG_EVERY = 20L
    }
}
