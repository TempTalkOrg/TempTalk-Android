package com.difft.android.websocket.api.websocket

import com.difft.android.websocket.internal.websocket.WebSocketConnection




interface HealthMonitor {
    fun onKeepAliveResponse()

    fun monitor(webSocketConnection: WebSocketConnection)

    fun stopMonitoring(webSocketConnection: WebSocketConnection?)

    fun doConnectInternal(webSocketConnection: WebSocketConnection){
        webSocketConnection.connect()
    }
}
