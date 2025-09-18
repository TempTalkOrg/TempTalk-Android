package com.difft.android.websocket.api.websocket

import com.difft.android.websocket.internal.websocket.WebSocketConnection

/**
 * Callbacks to provide WebSocket health information to a monitor.
 */
interface HealthMonitor {
    fun onKeepAliveResponse()

    fun monitor(webSocketConnection: WebSocketConnection)

    fun stopMonitoring(webSocketConnection: WebSocketConnection?)

    fun doConnectInternal(webSocketConnection: WebSocketConnection){
        webSocketConnection.connect()
    }
}
