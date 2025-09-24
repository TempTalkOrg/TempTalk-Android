package com.difft.android.websocket.api.websocket

import com.difft.android.websocket.internal.websocket.WebSocketConnection
import org.whispersystems.signalservice.internal.websocket.webSocketRequestMessage

interface KeepAliveSender {
    fun sendKeepAliveFrom(webSocketConnection: WebSocketConnection)
}

class WebSocketKeepAliveSender : KeepAliveSender {
    override fun sendKeepAliveFrom(webSocketConnection: WebSocketConnection) {
        webSocketConnection.runCatching {
            webSocketRequestMessage {
                requestId = System.currentTimeMillis()
                path = "/v1/keepalive"
                verb = "GET"
            }.let(::sendRequest)
        }
    }
}
