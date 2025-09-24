package com.difft.android.websocket.internal.websocket

import com.difft.android.base.log.lumberjack.L
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class ControllableWebSocketListener(
    private val websocketListener: WebSocketListener,
) : WebSocketListener() {

    @Volatile
    private var invalid = false

    private val id: String = "[chat:" + System.identityHashCode(this) + "]"

    fun invalidate() {
        invalid = true
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (invalid) return
        L.i { "[$id]: onOpen" }
        websocketListener.onOpen(webSocket, response)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (invalid) return
        L.d { "[$id]: onMessage" }
        websocketListener.onMessage(webSocket, bytes)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (invalid) return
        L.d { "[$id]: onMessage" }
        websocketListener.onMessage(webSocket, text)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        if (invalid) return
        L.i { "[$id]: onClosing" }
        websocketListener.onClosing(webSocket, code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (invalid) return
        L.i { "[$id]: onClosed" }
        websocketListener.onClosed(webSocket, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (invalid) return
        L.i { "[$id]: onFailure" }
        websocketListener.onFailure(webSocket, t, response)
    }
}