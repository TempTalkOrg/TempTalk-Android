package org.thoughtcrime.securesms.websocket.monitor

import android.content.Context
import android.text.TextUtils
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.network.WebsocketUrlManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.jobmanager.impl.BackoffUtil
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.util.AppForegroundObserver
import com.difft.android.websocket.api.websocket.HealthMonitor
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import com.difft.android.websocket.internal.websocket.WebSocketConnection
import com.difft.android.websocket.internal.websocket.value
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
    private val websocketUrlManager: WebsocketUrlManager,
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

    private fun handleConnectingTimeout(connection: WebSocketConnection) {
        var timeoutJob: Job? = null
        val job = connection.webSocketConnectionState.onEach { state ->
            if (!isMonitoring) return@onEach

            if (state is WebSocketConnectionState.CONNECTING) {
                timeoutJob?.cancel()
                // Start the timeout coroutine only for CONNECTING state
                val timeoutDuration = calculateConnectingTimeout()
                L.i { "${connection.name} [ws]monitor: Starting timeout coroutine for state: $state" }
                timeoutJob = launch {
                    delay(timeoutDuration)
                    if (isMonitoring) {
                        timeoutJob = null
                        L.i { "${connection.name} [ws]monitor: Timeout for connecting, canceling connection. Timeout duration: $timeoutDuration" }
                        switchHost(connection)
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

    private fun calculateConnectingTimeout(): Long {
        val hostCount = websocketUrlManager.getChatWebsocketHostCounts()

        val rawExponent = attempts / (hostCount + 1) + 1 //loop through all hosts with same timeout value every patch

        // 1) Pick a max exponent so we don't overflow.
        //    e.g., 2^12 = 4096; 2^14 = 16384, etc.
        val maxExponent = 12              // Adjust up or down as you like
        val safeExponent = minOf(rawExponent, maxExponent)

        // 2) Compute 2^exponent safely:
        return minOf((1L shl safeExponent) * 1000, 60_000) // in milliseconds at most 60s
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
            if (it is WebSocketConnectionState.UNKNOWN_HOST_FAILED) {
                switchHost(webSocketConnection)
            }
            if (it is WebSocketConnectionState.FAILED
                || it is WebSocketConnectionState.DISCONNECTED
                || it is WebSocketConnectionState.AUTHENTICATION_FAILED
                || it is WebSocketConnectionState.UNKNOWN_HOST_FAILED
                || it is WebSocketConnectionState.INACTIVE_FAILED
            ) {
                L.i { "${webSocketConnection.name} [ws]monitor: start trigger doConnect, webSocketConnectionState: $it" }
                handleRedoConnect(webSocketConnection)
                L.i { "${webSocketConnection.name} [ws]monitor: finished trigger doConnect, webSocketConnectionState: $it" }
            } else if (it is WebSocketConnectionState.CONNECTED) {
                attempts = 0 // reset attempts when connected
                websocketUrlManager.recordCurrentChatHostAsSuccess()
            }
        }.launchIn(this)
        monitoringJobs.add(job)
    }

    private fun switchHost(connection: WebSocketConnection) {
        L.i { "${connection.name} [ws]monitor: switch host and clear last success connected host" }
        websocketUrlManager.switchToNextChatWebsocketHost()
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
            val backoff =
                BackoffUtil.exponentialBackoff(attempts, TimeUnit.SECONDS.toMillis(5))
            val finalAttempts = attempts
            L.w { "${webSocketConnection.name} Too many failed connection attempts,  attempts: $finalAttempts backing off: $backoff" }
            delay(backoff)
        }
    }

    private fun didNotLogin(): Boolean {
        val basicAuth = SecureSharedPrefsUtil.getBasicAuth()
        return TextUtils.isEmpty(basicAuth)
    }

    companion object {
        private val KEEP_ALIVE_SEND_CADENCE =
            TimeUnit.SECONDS.toMillis(WebSocketConnection.KEEP_ALIVE_TIMEOUT_SECONDS.toLong())
        private val MAX_TIME_SINCE_SUCCESSFUL_KEEP_ALIVE = KEEP_ALIVE_SEND_CADENCE * 3
    }
}