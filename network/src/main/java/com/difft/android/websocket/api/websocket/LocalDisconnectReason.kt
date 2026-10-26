package com.difft.android.websocket.api.websocket

/**
 * Why this app dropped a WebSocket itself ([com.difft.android.websocket.internal.websocket.WebSocketConnection.cancelConnection]
 * and `disconnectWhenConnected`). The reconnect policy needs it because a local drop looks like a
 * plain `DISCONNECTED` and says nothing about the host: only the host-attributable reasons count
 * as a host failure (#1396).
 */
enum class LocalDisconnectReason {
    /** The connect deadline passed before the handshake finished: the host did not answer. */
    CONNECT_TIMEOUT,

    /** Keep-alives went unanswered on a connected socket: the host stopped answering. */
    HEARTBEAT_LOST,

    /** A proxy toggle forces a fresh socket; the host did nothing wrong. */
    PROXY_CHANGE,

    /** Monitoring was stopped, on app stop or logout. */
    STOP;

    /** Whether the host should be treated as failed, so the next connect moves to another one. */
    val switchesHost: Boolean
        get() = this == CONNECT_TIMEOUT || this == HEARTBEAT_LOST
}
