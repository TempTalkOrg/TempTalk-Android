package com.difft.android.chat.websocket.monitor

import com.difft.android.base.utils.globalServices

import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.base.monitor.BackgroundPowerSampler
import com.difft.android.base.monitor.PowerEvent
import com.difft.android.base.utils.NetworkUtils
import com.difft.android.base.utils.appScope
import com.difft.android.network.UrlManager
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import com.difft.android.websocket.api.websocket.HealthMonitor
import com.difft.android.websocket.api.websocket.LocalDisconnectReason
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







@Singleton
class WebSocketHealthMonitor
@Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val urlManager: UrlManager,
    private val coordinator: DomainSpeedTestCoordinator,
    private val keepAliveWakeAlarm: KeepAliveWakeAlarm,
    private val powerSampler: BackgroundPowerSampler,
    private val messagingGate: MessagingGate,
) : HealthMonitor, CoroutineScope by appScope {

    @Volatile
    private var lastHeartbeatResponseTime: Long = System.currentTimeMillis()

    
    
    
    @Volatile
    private var lastHeartbeatResponseElapsed: Long = SystemClock.elapsedRealtime()

    @Volatile
    private var lastHeartbeatSendTime: Long = System.currentTimeMillis()

    
    
    @Volatile
    private var lastHeartbeatSendElapsed: Long = 0L

    
    
    @Volatile
    private var socketGeneration = 0L

    
    @Volatile
    private var keepAliveTicks = 0L

    





    private val notificationFlow = MutableSharedFlow<WakeReason>(
        replay = 0,
        extraBufferCapacity = 1
    )

    private val _monitorWakeTicks = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1
    )

    



    val monitorWakeTicks: SharedFlow<Unit> = _monitorWakeTicks

    private var staleProbeJob: Job? = null

    @Volatile
    private var attempts = 0

    @Volatile
    private var isMonitoring = false

    private var monitoringJobs = mutableListOf<Job>()

    private var networkMonitor: NetworkMonitor? = null

    
    private var foregroundListener: AppForegroundObserver.Listener? = null

    private suspend fun waitForNetworkFine(websocketName: String) {
        var waitStart = 0L
        while (!NetworkConstraint.isNetworkAvailable(context)) {
            if (waitStart == 0L) {
                waitStart = SystemClock.elapsedRealtime()
                L.i { "$websocketName [ws]monitor network unavailable, waiting ${NetworkUtils.unavailabilityDiagnostics(context)}" }
            }
            
            try {
                withTimeout(NETWORK_WAIT_TIMEOUT) {
                    notificationFlow.first()
                }
            } catch (_: TimeoutCancellationException) {
                
            }
        }
        if (waitStart != 0L) {
            L.i { "$websocketName [ws]monitor network available after ${SystemClock.elapsedRealtime() - waitStart}ms" }
        }
    }

    override fun onKeepAliveResponse() {
        lastHeartbeatResponseTime = System.currentTimeMillis()
        lastHeartbeatResponseElapsed = SystemClock.elapsedRealtime()
        keepAliveWakeAlarm.cancelResponseDeadline()
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
            
            coordinator.onNetworkAvailable()
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
                connection.disconnectWhenConnected(LocalDisconnectReason.STOP)
            } catch (e: Exception) {
                L.w { "[ws]monitor: Error disconnecting WebSocket: ${e.message}" }
            }
        }
    }

    





    fun onAlarmTriggered() {
        
        
        if (isMonitoring) L.d { "[ws]monitor: alarm" } else L.w { "[ws]monitor: alarm fired while not monitoring" }
        attempts = 0
        powerSampler.onEvent(PowerEvent.WAKE_SERVICE)

        notificationFlow.tryEmit(WakeReason.SERVICE_ALARM)
    }

    private fun handleConnectingTimeout(connection: WebSocketConnection) {
        var timeoutJob: Job? = null
        val job = connection.webSocketConnectionState.onEach { state ->
            if (!isMonitoring) return@onEach

            if (state is WebSocketConnectionState.CONNECTING) {
                timeoutJob?.cancel()
                
                timeoutJob = launch {
                    delay(CONNECTING_TIMEOUT)
                    if (isMonitoring) {
                        timeoutJob = null
                        L.i { "${connection.name} [ws]monitor: connecting timeout after ${CONNECTING_TIMEOUT}ms, canceling connection" }
                        connection.cancelConnection(LocalDisconnectReason.CONNECT_TIMEOUT)
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
                powerSampler.onTick()
                if (wakeReason == WakeReason.KEEP_ALIVE_ALARM) powerSampler.onEvent(PowerEvent.WAKE_KEEP_ALIVE)

                
                
                if (wakeReason == WakeReason.CONNECTED) continue

                val triggeredByExternalEvent = wakeReason != null && wakeReason != WakeReason.KEEP_ALIVE_ALARM
                if (webSocketConnection.webSocketConnectionState.value == WebSocketConnectionState.CONNECTED) {
                    val previousSend = lastHeartbeatSendElapsed
                    
                    
                    val unanswered = keepAliveUnansweredForCadence(wakeReason, previousSend, lastHeartbeatResponseElapsed)
                    if (unanswered) {
                        L.w { "${webSocketConnection.name} [ws]monitor: keep-alive unanswered for a cadence, probing sinceSend=${SystemClock.elapsedRealtime() - previousSend}ms" }
                    }
                    
                    if (lastHeartbeatSendTime - lastHeartbeatResponseTime > MAX_TIME_SINCE_SUCCESSFUL_KEEP_ALIVE) {
                        L.w { "${webSocketConnection.name} [ws]monitor: missed keep-alives, disconnecting lastSend=$lastHeartbeatSendTime lastResponse=$lastHeartbeatResponseTime" }
                        powerSampler.onEvent(PowerEvent.KEEP_ALIVE_MISSED)
                        webSocketConnection.disconnectWhenConnected(LocalDisconnectReason.HEARTBEAT_LOST)
                    } else {
                        val sinceLastResponse = SystemClock.elapsedRealtime() - lastHeartbeatResponseElapsed
                        
                        
                        
                        val tick = ++keepAliveTicks
                        if (triggeredByExternalEvent || tick % KEEP_ALIVE_LOG_EVERY == 0L) {
                            L.i { "${webSocketConnection.name} [ws]monitor: keep alive n=$tick wake=${wakeReason ?: "timer"} sinceResponse=${sinceLastResponse}ms" }
                        }
                        
                        val deadlineArmed = armResponseDeadline(webSocketConnection)
                        lastHeartbeatSendElapsed = SystemClock.elapsedRealtime()
                        webSocketConnection.sendKeepAlive()
                        lastHeartbeatSendTime = System.currentTimeMillis()
                        powerSampler.onEvent(PowerEvent.KEEP_ALIVE_SENT)
                        val longSilence = wakeReason != null && sinceLastResponse > keepAliveCadence() * STALE_PROBE_SILENCE_CADENCES
                        if (unanswered || (!deadlineArmed && longSilence)) {
                            
                            
                            
                            armStaleProbe(webSocketConnection)
                        }
                    }
                }
            }
        }
        monitoringJobs.add(job)
    }

    






    private suspend fun awaitKeepAliveTick(webSocketConnection: WebSocketConnection): WakeReason? {
        val stateFlow = webSocketConnection.webSocketConnectionState
        val connected = stateFlow.value == WebSocketConnectionState.CONNECTED
        val armed = connected && keepAliveWakeAlarm.arm { notificationFlow.tryEmit(WakeReason.KEEP_ALIVE_ALARM) }
        
        
        
        val wakes = if (connected) {
            notificationFlow
        } else {
            merge(
                notificationFlow,
                stateFlow.filter { it == WebSocketConnectionState.CONNECTED }.map { WakeReason.CONNECTED },
            )
        }
        return try {
            withTimeout(keepAliveCadence()) {
                wakes.first()
            }
        } catch (_: TimeoutCancellationException) {
            null
        } finally {
            if (armed) keepAliveWakeAlarm.cancelWake()
        }
    }

    




    private fun keepAliveCadence(): Long =
        if (AppForegroundObserver.isForegrounded()) KEEP_ALIVE_SEND_CADENCE else KeepAliveWakeAlarm.CADENCE_MS

    





    private fun armResponseDeadline(webSocketConnection: WebSocketConnection): Boolean {
        val sentElapsed = SystemClock.elapsedRealtime()
        val generation = socketGeneration
        return keepAliveWakeAlarm.armResponseDeadline {
            launch {
                if (!isMonitoring) return@launch
                if (socketGeneration != generation) return@launch
                if (lastHeartbeatResponseElapsed >= sentElapsed) return@launch
                if (webSocketConnection.webSocketConnectionState.value != WebSocketConnectionState.CONNECTED) return@launch
                L.w { "${webSocketConnection.name} [ws]monitor: keep-alive response deadline missed deadline=${KeepAliveWakeAlarm.RESPONSE_DEADLINE_MS}ms, force reconnecting" }
                powerSampler.onEvent(PowerEvent.KEEP_ALIVE_MISSED)
                webSocketConnection.disconnectWhenConnected(LocalDisconnectReason.HEARTBEAT_LOST)
            }
        }
    }

    




    private fun armStaleProbe(webSocketConnection: WebSocketConnection) {
        if (staleProbeJob?.isActive == true) return
        val probeStartedElapsed = SystemClock.elapsedRealtime()
        L.i { "${webSocketConnection.name} [ws]monitor: arming stale-connection probe (${STALE_PROBE_TIMEOUT}ms)" }
        staleProbeJob = launch {
            
            
            
            
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
            powerSampler.onEvent(PowerEvent.KEEP_ALIVE_MISSED)
            webSocketConnection.disconnectWhenConnected(LocalDisconnectReason.HEARTBEAT_LOST)
        }
    }

    private fun checkUnconnectedStateAndReconnect(webSocketConnection: WebSocketConnection) {
        val job = webSocketConnection.webSocketConnectionState.onEach { state ->
            if (!isMonitoring) return@onEach

            L.i { "${webSocketConnection.name} [ws]monitor: state=$state" }
            
            
            
            if (state != WebSocketConnectionState.CONNECTING) {
                socketGeneration++
                keepAliveWakeAlarm.cancelResponseDeadline()
                lastHeartbeatSendElapsed = 0L
            }
            val policy = reconnectPolicyFor(
                state,
                webSocketConnection.closedByServer,
                webSocketConnection.localDisconnectReason,
            )
            when (policy) {
                ReconnectPolicy.RECONNECT_SWITCH_HOST -> {
                    coordinator.onWsFailure()
                    
                    switchHost(webSocketConnection)
                    handleRedoConnect(webSocketConnection)
                }

                ReconnectPolicy.RECONNECT_KEEP_HOST -> {
                    if (webSocketConnection.closedByServer) {
                        
                        L.i { "${webSocketConnection.name} [ws]monitor: closed by server, reconnect without host switch" }
                    } else if (state == WebSocketConnectionState.DISCONNECTED) {
                        L.i { "${webSocketConnection.name} [ws]monitor: local disconnect reason=${webSocketConnection.localDisconnectReason}, reconnect without host switch" }
                    } else {
                        L.w { "${webSocketConnection.name} [ws]monitor: auth-level failure $state, reconnect without host switch" }
                    }
                    handleRedoConnect(webSocketConnection)
                }

                ReconnectPolicy.CONNECTED -> {
                    coordinator.onWsConnected()
                    
                    urlManager.recordChatWebsocketConnected()
                    powerSampler.onEvent(PowerEvent.RECONNECTED)
                    attempts = 0 
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
        if (didNotLogin()) { 
            L.i { "${webSocketConnection.name} wait until login status" }
            waitUntilLoginStatus()
            L.i { "${webSocketConnection.name} login status, go on do connect" }
        }
        delayWhenRetry(webSocketConnection)
        waitForNetworkFine(webSocketConnection.name)
        waitUntilMessagingOpen(webSocketConnection.name)
    }

    




    private suspend fun waitUntilMessagingOpen(websocketName: String) {
        if (messagingGate.isOpen) return
        L.i { "$websocketName [MigrationPause] event=connect_deferred state=${messagingGate.state.javaClass.simpleName}" }
        messagingGate.onChanged.first { it == MessagingPauseState.Open }
    }

    private suspend fun waitUntilLoginStatus() {
        while (didNotLogin()) {
            delay(1000)
        }
    }

    private suspend fun delayWhenRetry(webSocketConnection: WebSocketConnection) {
        if (++attempts > 1) {
            
            val backoff = BackoffUtil.exponentialBackoff(attempts, reconnectBackoffCapMs(AppForegroundObserver.isForegrounded()))

            L.w { "${webSocketConnection.name} [ws]monitor: backoff attempts=$attempts delay=${backoff}ms" }

            try {
                withTimeout(backoff) {
                    notificationFlow.first()
                }
                L.i { "${webSocketConnection.name} [ws]monitor: backoff interrupted by external trigger" }
            } catch (_: TimeoutCancellationException) {
                
            }
        }
    }

    private fun didNotLogin(): Boolean {
        val basicAuth = (globalServices.userManager.getUserData()?.baseAuth ?: "")
        return TextUtils.isEmpty(basicAuth)
    }

    companion object {
        
        
        private const val CONNECTING_TIMEOUT = 10_000L

        
        
        private const val NETWORK_WAIT_TIMEOUT = 5_000L

        private val KEEP_ALIVE_SEND_CADENCE =
            TimeUnit.SECONDS.toMillis(WebSocketConnection.KEEP_ALIVE_TIMEOUT_SECONDS.toLong())
        private val MAX_TIME_SINCE_SUCCESSFUL_KEEP_ALIVE = KEEP_ALIVE_SEND_CADENCE * 3

        
        
        private const val STALE_PROBE_TIMEOUT = 10_000L

        
        
        
        
        private const val STALE_PROBE_SILENCE_CADENCES = 2

        
        private const val KEEP_ALIVE_LOG_EVERY = 20L
    }
}
