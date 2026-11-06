package com.difft.android.chat.websocket

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.IConnectionRefresher
import com.difft.android.chat.focus.GroupFocusSocketSync
import com.difft.android.chat.messages.IncomingMessageObserver
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.api.websocket.LocalDisconnectReason
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import com.difft.android.websocket.internal.websocket.value
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages WebSocket connection lifecycle and message observer.
 * This class coordinates between AppWebSocketHelper and IncomingMessageObserver.
 *
 * Two independent flags (#1382): [isStarted] says the observer and the session guard run;
 * [isSocketPaused] keeps the socket closed while the migration message pause is in effect. The
 * socket starts paused because messaging stays closed until the pause has loaded;
 * `MessagingPauseReceiveCoordinator` drives [pause] and [resume].
 */
@Singleton
class WebSocketManager @Inject constructor(
    private val appWebSocketHelper: AppWebSocketHelper,
    private val incomingMessageObserver: IncomingMessageObserver,
    private val sessionAuthGuard: SessionAuthGuard,
    private val groupFocusSocketSync: GroupFocusSocketSync,
) : IConnectionRefresher {
    // @Volatile: read by reconnectAfterProxyChange() outside the @Synchronized
    // start/stop/reset path. Without @Volatile, a writer thread's update would
    // not be guaranteed to be visible to a non-synchronized reader on a
    // different thread (JMM happens-before only holds between synchronized
    // accesses on the same monitor).
    @Volatile
    private var isStarted = false

    @Volatile
    private var isSocketPaused = true

    /**
     * Start WebSocket connection and message observer
     */
    @Synchronized
    fun start() {
        if (isStarted) {
            L.d { "[ws] Already started, ignoring duplicate call" }
            return
        }

        L.i { "[ws] Starting WebSocket connection and message observer" }
        isStarted = true

        // Start WebSocket monitoring (includes auto-reconnect logic); a paused socket starts on resume().
        if (!isSocketPaused) appWebSocketHelper.chatDataWebSocketConnection.startMonitoring()

        // Start message observer immediately
        incomingMessageObserver.start()

        // The guard owns the kick-out decision for as long as there is a session to protect.
        // Its own job?.isActive check makes a sticky-service restart of start() a no-op.
        sessionAuthGuard.start()

        groupFocusSocketSync.start()

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

        sessionAuthGuard.stop()
        groupFocusSocketSync.stop()

        // Stop WebSocket monitoring; a paused socket has none and is already closed.
        if (!isSocketPaused) appWebSocketHelper.chatDataWebSocketConnection.stopMonitoring()

        L.i { "[ws] WebSocket connection and message observer stopped" }
    }

    /**
     * Closes the socket and keeps it closed: no reconnect loop, no keep-alive. Frames read but not
     * ACKed are dropped and stay queued on the server. The observer and the session guard keep
     * running, so [resume] never has to restart the observer.
     */
    @Synchronized
    fun pause() {
        if (isSocketPaused) return
        isSocketPaused = true
        val conn = appWebSocketHelper.chatDataWebSocketConnection
        if (isStarted) conn.stopMonitoring()
        // A pause is local, not a host failure: STOP keeps the current host.
        conn.cancelConnection(LocalDisconnectReason.STOP)
        val dropped = conn.clearIncomingRequests()
        L.i { "[MigrationPause] event=socket_pause started=$isStarted droppedFrames=$dropped" }
    }

    /** Lets the socket connect again; restarts monitoring only, never the observer. */
    @Synchronized
    fun resume() {
        if (!isSocketPaused) return
        isSocketPaused = false
        L.i { "[MigrationPause] event=socket_resume started=$isStarted" }
        if (isStarted) appWebSocketHelper.chatDataWebSocketConnection.startMonitoring()
    }

    fun isSocketPaused(): Boolean = isSocketPaused

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
        if (!isStarted || isSocketPaused) {
            L.i { "[ws] reconnectAfterProxyChange: started=$isStarted paused=$isSocketPaused, no-op" }
            return
        }
        val conn = appWebSocketHelper.chatDataWebSocketConnection
        val state = conn.webSocketConnectionState.value
        when (state) {
            WebSocketConnectionState.CONNECTED, WebSocketConnectionState.CONNECTING -> {
                L.i { "[ws] reconnectAfterProxyChange: state=$state, evicting pool + cancelling to force fresh connect" }
                conn.evictConnectionPool()
                conn.cancelConnection(LocalDisconnectReason.PROXY_CHANGE)
                // healthMonitor (started via startMonitoring()) re-establishes
                // and the new attempt reads the updated ProxyConfigProvider.current.
            }
            else -> {
                L.i { "[ws] reconnectAfterProxyChange: state=$state, no active socket; next connect picks up new state" }
            }
        }
    }
}
