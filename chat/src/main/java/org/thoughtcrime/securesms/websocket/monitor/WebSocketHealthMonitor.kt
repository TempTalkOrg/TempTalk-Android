package org.thoughtcrime.securesms.websocket.monitor

import android.content.Context
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.network.UrlManager
import com.difft.android.websocket.api.websocket.HealthMonitor
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import com.difft.android.websocket.internal.websocket.WebSocketConnection
import com.difft.android.websocket.internal.websocket.value
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.thoughtcrime.securesms.jobmanager.impl.BackoffUtil
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.util.AppForegroundObserver
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * The monitor is also responsible for sending heartbeats/keep-alive messages to prevent
 * timeouts, if timeout then disconnect the websocket connection. and then the connection will be reconnected inside [checkUnconnectedStateAndReconnect].
 *
 * The monitor lifecycle is same to the application, it will only be destroyed when the application is destroyed.
 */
class WebSocketHealthMonitor
@Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val urlManager: UrlManager,
) : HealthMonitor, CoroutineScope by appScope {

    @Volatile
    private var lastHeartbeatResponseTime: Long = System.currentTimeMillis()

    @Volatile
    private var lastHeartbeatSendTime: Long = System.currentTimeMillis()

    private val notificationChannel =
        Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var attempts = 0

    @Volatile
    private var isMonitoring = false

    private var monitoringJobs = mutableListOf<Job>()

    private var networkMonitor: NetworkMonitor? = null

    // 添加前台观察者监听器引用，用于清理
    private var foregroundListener: AppForegroundObserver.Listener? = null

    private suspend fun waitForNetworkFine(websocketName: String) {
        try {
            while (!NetworkConstraint.isNetworkAvailable(context)) {
                L.i { "$websocketName [ws]monitor Network is not fine, waiting..." }
                notificationChannel.receive()
            }
        } catch (e: InterruptedException) {
            throw AssertionError(e)
        }
        L.i { "$websocketName [ws]monitor Network is fine now" }
    }

    override fun onKeepAliveResponse() {
        lastHeartbeatResponseTime = System.currentTimeMillis()
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
                    val job = launch { notificationChannel.send(Unit) }
                    monitoringJobs.add(job)
                }
            }
        }
        AppForegroundObserver.addListener(foregroundListener!!)

        networkMonitor = NetworkMonitor(context) {
            if (isMonitoring) {
                val job = launch { notificationChannel.send(Unit) }
                monitoringJobs.add(job)
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

        // 移除前台观察者监听器
        foregroundListener?.let {
            AppForegroundObserver.removeListener(it)
            foregroundListener = null
        }

        // 停止网络监控
        networkMonitor?.unregister()
        networkMonitor = null

        // 关闭通知通道
        notificationChannel.close()

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
     * 当 AlarmManager 触发时调用，用于加速 WebSocket 重连
     *
     * 作用：
     * 1. 重置重试次数，下次重连时跳过 backoff delay
     * 2. 发送通知信号，如果当前正在 backoff 等待中，可以立即打断
     *
     * 注意：
     * - 即使 isMonitoring=false 也会执行，确保首次启动也能受益
     * - 重置 attempts 没有副作用
     * - notificationChannel.send() 如果没有接收者会自动丢弃（BufferOverflow.DROP_OLDEST）
     */
    fun onAlarmTriggered() {
        L.i { "[ws]monitor: Alarm triggered (monitoring=$isMonitoring), resetting retry attempts and notifying" }
        attempts = 0  // 重置重试次数，即使未启动也能在后续启动时生效

        // 发送通知，打断可能正在进行的 backoff delay
        // 如果当前没有监控，通知会被自动丢弃（BufferOverflow.DROP_OLDEST）
        if (isMonitoring) {
            launch {
                notificationChannel.send(Unit)
            }
        }
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
                delay(KEEP_ALIVE_SEND_CADENCE)
                if (!isMonitoring) break

                L.i { "${webSocketConnection.name} [ws]monitor: start check websocket healthy" }
                if (webSocketConnection.webSocketConnectionState.value == WebSocketConnectionState.CONNECTED) {
                    //why use lastHeartbeatSendTime instead of System.currentTimeMillis()? in case of App may be frozen in background
                    if (lastHeartbeatSendTime - lastHeartbeatResponseTime > MAX_TIME_SINCE_SUCCESSFUL_KEEP_ALIVE) {
                        L.w { "${webSocketConnection.name} [ws]monitor: Missed keep lives, disconnect current websocket connection" }
                        webSocketConnection.disconnectWhenConnected()
                    } else {
                        L.i { "${webSocketConnection.name} [ws]monitor: send keep alive" }
                        webSocketConnection.sendKeepAlive()
                        lastHeartbeatSendTime = System.currentTimeMillis()
                    }
                }
            }
        }
        monitoringJobs.add(job)
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
                // 任何连接失败都切换 host，下次重连会使用新的 host
                switchHost(webSocketConnection)
                L.i { "${webSocketConnection.name} [ws]monitor: start trigger doConnect, webSocketConnectionState: $it" }
                handleRedoConnect(webSocketConnection)
                L.i { "${webSocketConnection.name} [ws]monitor: finished trigger doConnect, webSocketConnectionState: $it" }
            } else if (it is WebSocketConnectionState.CONNECTED) {
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

            // 使用 withTimeout + notificationChannel.receive() 替代纯粹的 delay
            // 这样可以被 Alarm、网络变化、前台切换等事件立即打断
            try {
                withTimeout(backoff) {
                    notificationChannel.receive()  // 等待外部唤醒信号
                }
                L.i { "${webSocketConnection.name} Backoff interrupted by external trigger (Alarm/Network/Foreground)" }
            } catch (_: TimeoutCancellationException) {
                // 正常超时，继续重连流程
                L.i { "${webSocketConnection.name} Backoff completed normally: $backoff ms" }
            }
        }
    }

    private fun didNotLogin(): Boolean {
        val basicAuth = SecureSharedPrefsUtil.getBasicAuth()
        return TextUtils.isEmpty(basicAuth)
    }

    companion object {
        // 连接超时时间：10 秒
        // - 足够判断连接是否能建立（现代网络通常 3-5 秒内连接）
        // - 配合 host 切换机制，快速失败后尝试其他 host
        private const val CONNECTING_TIMEOUT = 10_000L

        private val KEEP_ALIVE_SEND_CADENCE =
            TimeUnit.SECONDS.toMillis(WebSocketConnection.KEEP_ALIVE_TIMEOUT_SECONDS.toLong())
        private val MAX_TIME_SINCE_SUCCESSFUL_KEEP_ALIVE = KEEP_ALIVE_SEND_CADENCE * 3
    }
}