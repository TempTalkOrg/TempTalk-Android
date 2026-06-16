package com.difft.android.chat.websocket

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.IConnectionRefresher
import com.difft.android.chat.messages.IncomingMessageObserver
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import com.difft.android.websocket.internal.websocket.value
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages WebSocket connection lifecycle and message observer.
 * This class coordinates between AppWebSocketHelper and IncomingMessageObserver.
 */
@Singleton
class WebSocketManager @Inject constructor(
    private val appWebSocketHelper: AppWebSocketHelper,
    private val incomingMessageObserver: IncomingMessageObserver
) : IConnectionRefresher {
    // @Volatile: read by reconnectAfterProxyChange() outside the @Synchronized
    // start/stop/reset path. Without @Volatile, a writer thread's update would
    // not be guaranteed to be visible to a non-synchronized reader on a
    // different thread (JMM happens-before only holds between synchronized
    // accesses on the same monitor).
    @Volatile
    private var isStarted = false

    /**
     * Start WebSocket connection and message observer
     */
    @Synchronized
    fun start() {
        if (isStarted) {
            L.w { "[ws] Already started, ignoring duplicate call" }
            return
        }

        L.i { "[ws] Starting WebSocket connection and message observer" }
        isStarted = true

        // Start WebSocket monitoring (includes auto-reconnect logic)
        appWebSocketHelper.chatDataWebSocketConnection.startMonitoring()

        // Start message observer immediately
        incomingMessageObserver.start()

        L.i { "[ws] WebSocket connection and message observer started successfully" }
    }

    /**
     * Stop WebSocket connection and message observer
     */
    @Synchronized
    fun stop() {
        if (!isStarted) {
            L.w { "[ws] Not started, ignoring stop call" }
            return
        }

        L.i { "[ws] Stopping WebSocket connection and message observer" }
        isStarted = false

        // Stop message observer first
        incomingMessageObserver.stop()

        // Stop WebSocket monitoring
        appWebSocketHelper.chatDataWebSocketConnection.stopMonitoring()

        L.i { "[ws] WebSocket connection and message observer stopped" }
    }

    /**
     * Reset the started state (for app lifecycle management)
     */
    @Synchronized
    fun reset() {
        L.i { "[ws] Resetting WebSocket manager state" }
        isStarted = false
    }

    /**
     * Get the WebSocket connection for direct access
     */
    fun getWebSocketConnection() = appWebSocketHelper.chatDataWebSocketConnection

    /**
     * Check if WebSocket is connected
     */
    fun isConnected(): Boolean {
        return appWebSocketHelper.chatDataWebSocketConnection.webSocketConnectionState.value == WebSocketConnectionState.CONNECTED
    }

    /**
     * Check if WebSocket manager is started
     */
    fun isStarted(): Boolean {
        return isStarted
    }

    /**
     * Get current connection state
     */
    fun getConnectionState() = appWebSocketHelper.chatDataWebSocketConnection.webSocketConnectionState.value

    /**
     * Drop the current IM WebSocket (when connected) so the next reconnect picks
     * up the latest `ProxyConfigProvider` state. healthMonitor (started via
     * `startMonitoring()` in [start]) re-establishes the connection automatically.
     *
     * Fire-and-forget — see [IConnectionRefresher.reconnectAfterProxyChange] contract.
     */
    override fun reconnectAfterProxyChange() {
        if (!isStarted) {
            L.i { "[ws] reconnectAfterProxyChange: manager not started, no-op" }
            return
        }
        val conn = appWebSocketHelper.chatDataWebSocketConnection
        val state = conn.webSocketConnectionState.value
        when (state) {
            WebSocketConnectionState.CONNECTED, WebSocketConnectionState.CONNECTING -> {
                L.i { "[ws] reconnectAfterProxyChange: state=$state, evicting pool + cancelling to force fresh connect" }
                conn.evictConnectionPool()
                conn.cancelConnection()
                // healthMonitor (started via startMonitoring()) re-establishes
                // and the new attempt reads the updated ProxyConfigProvider.current.
            }
            else -> {
                L.i { "[ws] reconnectAfterProxyChange: state=$state, no active socket; next connect picks up new state" }
            }
        }
    }
}
