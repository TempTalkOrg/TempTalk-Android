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










@Singleton
class WebSocketManager @Inject constructor(
    private val appWebSocketHelper: AppWebSocketHelper,
    private val incomingMessageObserver: IncomingMessageObserver,
    private val sessionAuthGuard: SessionAuthGuard,
    private val groupFocusSocketSync: GroupFocusSocketSync,
) : IConnectionRefresher {
    
    
    
    
    
    @Volatile
    private var isStarted = false

    @Volatile
    private var isSocketPaused = true

    


    @Synchronized
    fun start() {
        if (isStarted) {
            L.d { "[ws] Already started, ignoring duplicate call" }
            return
        }

        L.i { "[ws] Starting WebSocket connection and message observer" }
        isStarted = true

        
        if (!isSocketPaused) appWebSocketHelper.chatDataWebSocketConnection.startMonitoring()

        
        incomingMessageObserver.start()

        
        
        sessionAuthGuard.start()

        groupFocusSocketSync.start()

        L.i { "[ws] WebSocket connection and message observer started successfully" }
    }

    


    @Synchronized
    fun stop() {
        if (!isStarted) {
            L.w { "[ws] Not started, ignoring stop call" }
            return
        }

        L.i { "[ws] Stopping WebSocket connection and message observer" }
        isStarted = false

        
        incomingMessageObserver.stop()

        sessionAuthGuard.stop()
        groupFocusSocketSync.stop()

        
        if (!isSocketPaused) appWebSocketHelper.chatDataWebSocketConnection.stopMonitoring()

        L.i { "[ws] WebSocket connection and message observer stopped" }
    }

    




    @Synchronized
    fun pause() {
        if (isSocketPaused) return
        isSocketPaused = true
        val conn = appWebSocketHelper.chatDataWebSocketConnection
        if (isStarted) conn.stopMonitoring()
        
        conn.cancelConnection(LocalDisconnectReason.STOP)
        val dropped = conn.clearIncomingRequests()
        L.i { "[MigrationPause] event=socket_pause started=$isStarted droppedFrames=$dropped" }
    }

    
    @Synchronized
    fun resume() {
        if (!isSocketPaused) return
        isSocketPaused = false
        L.i { "[MigrationPause] event=socket_resume started=$isStarted" }
        if (isStarted) appWebSocketHelper.chatDataWebSocketConnection.startMonitoring()
    }

    fun isSocketPaused(): Boolean = isSocketPaused

    


    @Synchronized
    fun reset() {
        L.i { "[ws] Resetting WebSocket manager state" }
        isStarted = false
    }

    


    fun getWebSocketConnection() = appWebSocketHelper.chatDataWebSocketConnection

    


    fun isConnected(): Boolean {
        return appWebSocketHelper.chatDataWebSocketConnection.webSocketConnectionState.value == WebSocketConnectionState.CONNECTED
    }

    


    fun isStarted(): Boolean {
        return isStarted
    }

    


    fun getConnectionState() = appWebSocketHelper.chatDataWebSocketConnection.webSocketConnectionState.value

    






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
                
                
            }
            else -> {
                L.i { "[ws] reconnectAfterProxyChange: state=$state, no active socket; next connect picks up new state" }
            }
        }
    }
}
