package com.difft.android.websocket.api.websocket





sealed class WebSocketConnectionState {

    override fun toString(): String {
        return this::class.simpleName.toString()
    }

    object DISCONNECTED : WebSocketConnectionState()
    object CONNECTING : WebSocketConnectionState()
    object CONNECTED : WebSocketConnectionState()
    object AUTHENTICATION_FAILED : WebSocketConnectionState()
    object UNKNOWN_HOST_FAILED : WebSocketConnectionState()
    object INACTIVE_FAILED : WebSocketConnectionState() 
    object FAILED : WebSocketConnectionState()
}
