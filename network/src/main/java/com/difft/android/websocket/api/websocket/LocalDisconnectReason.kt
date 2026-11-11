package com.difft.android.websocket.api.websocket







enum class LocalDisconnectReason {
    
    CONNECT_TIMEOUT,

    
    HEARTBEAT_LOST,

    
    PROXY_CHANGE,

    
    STOP;

    
    val switchesHost: Boolean
        get() = this == CONNECT_TIMEOUT || this == HEARTBEAT_LOST
}
