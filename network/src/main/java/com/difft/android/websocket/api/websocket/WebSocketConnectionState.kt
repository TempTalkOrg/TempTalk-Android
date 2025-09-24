package com.difft.android.websocket.api.websocket

/**
 * Represent the state of a single WebSocketConnection.
 */

sealed class WebSocketConnectionState {

    override fun toString(): String {
        return this::class.simpleName.toString()
    }

    object DISCONNECTED : WebSocketConnectionState()
    object CONNECTING : WebSocketConnectionState()
    object CONNECTED : WebSocketConnectionState()
    object AUTHENTICATION_FAILED : WebSocketConnectionState()
    object UNKNOWN_HOST_FAILED : WebSocketConnectionState()
    object INACTIVE_FAILED : WebSocketConnectionState() //设备超过三十天未活跃，连接时状态码会返回451
    object FAILED : WebSocketConnectionState()
}
