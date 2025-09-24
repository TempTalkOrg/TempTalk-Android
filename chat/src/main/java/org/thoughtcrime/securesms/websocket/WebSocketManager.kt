package org.thoughtcrime.securesms.websocket

import com.difft.android.base.log.lumberjack.L
import org.thoughtcrime.securesms.messages.IncomingMessageObserver
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
) {
    private var isStarted = false

    /**
     * Start WebSocket connection and message observer
     */
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
} 