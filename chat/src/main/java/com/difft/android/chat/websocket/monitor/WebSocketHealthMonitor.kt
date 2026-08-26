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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
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

    /**
     * 用于广播外部事件通知（网络变化、前台切换、Alarm 触发等）
     * 多个等待点可以同时收到通知
     */
    private val notificationFlow = MutableSharedFlow<Unit>(
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

    // 添加前台观察者监听器引用，用于清理
    private var foregroundListener: AppForegroundObserver.Listener? = null

    private suspend fun waitForNetworkFine(websocketName: String) {
        while (!NetworkConstraint.isNetworkAvailable(context)) {
            L.i { "$websocketName [ws]monitor Network is not fine, waiting..." }
            // 使用超时作为安全机制，防止在 first() 开始收集前网络已恢复导致错过通知
            // 超时后会重新检查网络状态，避免永远等待
            try {
                withTimeout(NETWORK_WAIT_TIMEOUT) {
                    notificationFlow.first()  // 等待网络变化通知
                }
            } catch (_: TimeoutCancellationException) {
                // 超时后重新检查网络状态
                L.i { "$websocketName [ws]monitor Network wait timeout, rechecking..." }
            }
        }
        L.i { "$websocketName [ws]monitor Network is fine now" }
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

        // 创建前台观察者监听器并保存引用
        foregroundListener = object : AppForegroundObserver.Listener {
            override fun onForeground() {
                if (isMonitoring) {
                    L.i { "[ws]monitor: App to foreground, notifying all receivers" }
                    notificationFlow.tryEmit(Unit)  // 使用 tryEmit 避免阻塞
                }
            }
        }
        AppForegroundObserver.addListener(foregroundListener!!)

        networkMonitor = NetworkMonitor(context) {
            if (isMonitoring) {
                L.i { "[ws]monitor: Network changed, notifying all receivers" }
                notificationFlow.tryEmit(Unit)  // 使用 tryEmit 避免阻塞
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

        // 取消所有监控任务
        monitoringJobs.forEach { it.cancel() }
        monitoringJobs.clear()

        staleProbeJob?.cancel()
        staleProbeJob = null

        // 移除前台观察者监听器
        foregroundListener?.let {
            AppForegroundObserver.removeListener(it)
            foregroundListener = null
        }

        // 停止网络监控
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
     * 当 AlarmManager 触发时调用，用于加速 WebSocket 重连和健康检查
     *
     * 作用：
     * 1. 重置重试次数，下次重连时跳过 backoff delay
     * 2. 发送通知信号，打断以下等待：
     *    - checkConnectedStateHealthy 中的健康检查等待（检测僵尸连接）
     *    - delayWhenRetry 中的 backoff 等待（加速重连）
     *    - waitForNetworkFine 中的等待
     *
     * 注意：
     * - 无条件发送通知，不依赖 isMonitoring 状态
     * - 闹钟的目的是确保后台连接活跃，即使监控状态异常也应该尝试触发检查
     * - 使用 tryEmit 不会阻塞，如果没有收集者通知会被缓冲或丢弃
     */
    fun onAlarmTriggered() {
        L.i { "[ws]monitor: Alarm triggered (monitoring=$isMonitoring), resetting retry attempts and notifying all receivers" }
        attempts = 0  // 重置重试次数，即使未启动也能在后续启动时生效

        notificationFlow.tryEmit(Unit)
    }

    private fun handleConnectingTimeout(connection: WebSocketConnection) {
        var timeoutJob: Job? = null
        val job = connection.webSocketConnectionState.onEach { state ->
            if (!isMonitoring) return@onEach

            if (state is WebSocketConnectionState.CONNECTING) {
                timeoutJob?.cancel()
                // Start the timeout coroutine only for CONNECTING state
                L.i { "${connection.name} [ws]monitor: Starting timeout coroutine for state: $state, timeout: ${CONNECTING_TIMEOUT}ms" }
                timeoutJob = launch {
                    delay(CONNECTING_TIMEOUT)
                    if (isMonitoring) {
                        timeoutJob = null
                        L.i { "${connection.name} [ws]monitor: Timeout for connecting, canceling connection" }
                        connection.cancelConnection()
                    }
                }
                // 将timeoutJob添加到监控列表中
                timeoutJob?.let { monitoringJobs.add(it) }
            } else {
                timeoutJob?.let {
                    L.i { "${connection.name} [ws]monitor: Canceling timeout coroutine for state: $state" }
                    it.cancel()
                    monitoringJobs.remove(it)  // 从监控列表中移除
                    timeoutJob = null
                }
            }
        }.launchIn(this)
        monitoringJobs.add(job)
    }

    private fun checkConnectedStateHealthy(webSocketConnection: WebSocketConnection) {
        val job = launch {
            while (isMonitoring) {
                // 等待超时或外部事件打断，解决 Doze 模式下 delay 被冻结的问题
                val triggeredByExternalEvent = try {
                    withTimeout(KEEP_ALIVE_SEND_CADENCE) {
                        notificationFlow.first()
                    }
                    true // 被外部事件唤醒
                } catch (_: TimeoutCancellationException) {
                    false // 正常超时
                }

                if (!isMonitoring) break

                _monitorWakeTicks.tryEmit(Unit)

                if (triggeredByExternalEvent) {
                    L.i { "${webSocketConnection.name} [ws]monitor: health check triggered by external event (Alarm/Network/Foreground)" }
                }

                L.i { "${webSocketConnection.name} [ws]monitor: start check websocket healthy" }
                if (webSocketConnection.webSocketConnectionState.value == WebSocketConnectionState.CONNECTED) {
                    //why use lastHeartbeatSendTime instead of System.currentTimeMillis()? in case of App may be frozen in background
                    if (lastHeartbeatSendTime - lastHeartbeatResponseTime > MAX_TIME_SINCE_SUCCESSFUL_KEEP_ALIVE) {
                        L.w { "${webSocketConnection.name} [ws]monitor: Missed keep lives, disconnect current websocket connection" }
                        webSocketConnection.disconnectWhenConnected()
                    } else {
                        val sinceLastResponse = SystemClock.elapsedRealtime() - lastHeartbeatResponseElapsed
                        L.i { "${webSocketConnection.name} [ws]monitor: send keep alive" }
                        webSocketConnection.sendKeepAlive()
                        lastHeartbeatSendTime = System.currentTimeMillis()
                        if (triggeredByExternalEvent && sinceLastResponse > STALE_PROBE_MIN_SILENCE) {
                            // Likely woken from a vendor freeze with a possibly-zombie socket;
                            // probe the keep-alive just sent instead of waiting up to 2 more cadences.
                            armStaleProbe(webSocketConnection)
                        }
                    }
                }
            }
        }
        monitoringJobs.add(job)
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
        val job = webSocketConnection.webSocketConnectionState.onEach {
            if (!isMonitoring) return@onEach

            L.i { "${webSocketConnection.name} [ws]monitor: receive webSocketConnectionState: $it" }
            if (it is WebSocketConnectionState.FAILED
                || it is WebSocketConnectionState.DISCONNECTED
                || it is WebSocketConnectionState.AUTHENTICATION_FAILED
                || it is WebSocketConnectionState.UNKNOWN_HOST_FAILED
                || it is WebSocketConnectionState.INACTIVE_FAILED
            ) {
                coordinator.onWsFailure()
                // 任何连接失败都切换 host，下次重连会使用新的 host
                switchHost(webSocketConnection)
                L.i { "${webSocketConnection.name} [ws]monitor: start trigger doConnect, webSocketConnectionState: $it" }
                handleRedoConnect(webSocketConnection)
                L.i { "${webSocketConnection.name} [ws]monitor: finished trigger doConnect, webSocketConnectionState: $it" }
            } else if (it is WebSocketConnectionState.CONNECTED) {
                coordinator.onWsConnected()
                attempts = 0 // reset attempts when connected
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
        L.i { "${webSocketConnection.name} [ws]monitor: all set, start do connect" }
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
            // 指数退避，最大5秒
            val backoff = BackoffUtil.exponentialBackoff(attempts, TimeUnit.SECONDS.toMillis(5))

            L.w { "${webSocketConnection.name} Too many failed connection attempts, attempts: $attempts backing off: $backoff ms" }

            // 等待超时或外部事件打断
            try {
                withTimeout(backoff) {
                    notificationFlow.first()
                }
                L.i { "${webSocketConnection.name} Backoff interrupted by external trigger (Alarm/Network/Foreground)" }
            } catch (_: TimeoutCancellationException) {
                // 正常超时，继续重连流程
                L.i { "${webSocketConnection.name} Backoff completed normally: $backoff ms" }
            }
        }
    }

    private fun didNotLogin(): Boolean {
        val basicAuth = (globalServices.userManager.getUserData()?.baseAuth ?: "")
        return TextUtils.isEmpty(basicAuth)
    }

    companion object {
        // 连接超时时间：10 秒
        // - 足够判断连接是否能建立（现代网络通常 3-5 秒内连接）
        // - 配合 host 切换机制，快速失败后尝试其他 host
        private const val CONNECTING_TIMEOUT = 10_000L

        // 网络等待超时时间：5秒
        // - 作为安全机制，防止在 first() 开始收集前网络已恢复导致错过通知
        // - 超时后会重新检查网络状态，避免永远等待
        private const val NETWORK_WAIT_TIMEOUT = 5_000L

        private val KEEP_ALIVE_SEND_CADENCE =
            TimeUnit.SECONDS.toMillis(WebSocketConnection.KEEP_ALIVE_TIMEOUT_SECONDS.toLong())
        private val MAX_TIME_SINCE_SUCCESSFUL_KEEP_ALIVE = KEEP_ALIVE_SEND_CADENCE * 3

        // Post-wake stale-connection probe deadline: generous for a slow-network round-trip,
        // far shorter than regular keep-alive miss accounting (up to 2 cadences).
        private const val STALE_PROBE_TIMEOUT = 10_000L

        // Minimum response silence before a probe may arm. 2x cadence: a healthy quiet
        // connection sits at ~1 cadence + RTT (phase jitter can cross 1x and cause spurious
        // probes on every 5-min alarm), while a real freeze leaves minutes of silence.
        private val STALE_PROBE_MIN_SILENCE = KEEP_ALIVE_SEND_CADENCE * 2
    }
}
