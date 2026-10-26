package com.difft.android.chat.websocket.monitor

import com.difft.android.websocket.api.websocket.LocalDisconnectReason
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [reconnectPolicyFor] — the branch matrix the health monitor consumes (issue #1230) —
 * and [reconnectBackoffCapMs], the foreground/background reconnect backoff cap (issue #1325).
 *
 * The point of the split is that an auth-level rejection must NOT switch host or count as a
 * connectivity failure: the server answered correctly and rejected the credential.
 */
class WebSocketReconnectPolicyTest {

    @Test
    fun `reconnect backoff keeps the fast 5 s cap in foreground and 30 s in background`() {
        assertEquals(5_000L, reconnectBackoffCapMs(isForegrounded = true))
        assertEquals(30_000L, reconnectBackoffCapMs(isForegrounded = false))
    }

    @Test
    fun `auth-level failures reconnect without switching host`() {
        assertEquals(
            ReconnectPolicy.RECONNECT_KEEP_HOST,
            reconnectPolicyFor(WebSocketConnectionState.AUTHENTICATION_FAILED)
        )
        assertEquals(
            ReconnectPolicy.RECONNECT_KEEP_HOST,
            reconnectPolicyFor(WebSocketConnectionState.INACTIVE_FAILED)
        )
    }

    @Test
    fun `connectivity failures still switch host`() {
        assertEquals(
            ReconnectPolicy.RECONNECT_SWITCH_HOST,
            reconnectPolicyFor(WebSocketConnectionState.FAILED)
        )
        assertEquals(
            ReconnectPolicy.RECONNECT_SWITCH_HOST,
            reconnectPolicyFor(WebSocketConnectionState.UNKNOWN_HOST_FAILED)
        )
    }

    @Test
    fun `CONNECTED and CONNECTING keep their own handling`() {
        assertEquals(ReconnectPolicy.CONNECTED, reconnectPolicyFor(WebSocketConnectionState.CONNECTED))
        assertEquals(ReconnectPolicy.IGNORE, reconnectPolicyFor(WebSocketConnectionState.CONNECTING))
    }

    @Test
    fun `a server-initiated close keeps the host and a transport failure switches`() {
        assertEquals(
            ReconnectPolicy.RECONNECT_KEEP_HOST,
            reconnectPolicyFor(WebSocketConnectionState.DISCONNECTED, closedByServer = true)
        )
        assertEquals(
            ReconnectPolicy.RECONNECT_SWITCH_HOST,
            reconnectPolicyFor(WebSocketConnectionState.FAILED, closedByServer = true)
        )
    }

    // T15 — a local disconnect says nothing about the host unless its reason does.
    @Test
    fun `a connect timeout or lost heartbeat switches host`() {
        assertEquals(
            ReconnectPolicy.RECONNECT_SWITCH_HOST,
            reconnectPolicyFor(
                WebSocketConnectionState.DISCONNECTED,
                localDisconnectReason = LocalDisconnectReason.CONNECT_TIMEOUT
            )
        )
        assertEquals(
            ReconnectPolicy.RECONNECT_SWITCH_HOST,
            reconnectPolicyFor(
                WebSocketConnectionState.DISCONNECTED,
                localDisconnectReason = LocalDisconnectReason.HEARTBEAT_LOST
            )
        )
    }

    @Test
    fun `a proxy change or a stop keeps the host`() {
        assertEquals(
            ReconnectPolicy.RECONNECT_KEEP_HOST,
            reconnectPolicyFor(
                WebSocketConnectionState.DISCONNECTED,
                localDisconnectReason = LocalDisconnectReason.PROXY_CHANGE
            )
        )
        assertEquals(
            ReconnectPolicy.RECONNECT_KEEP_HOST,
            reconnectPolicyFor(
                WebSocketConnectionState.DISCONNECTED,
                localDisconnectReason = LocalDisconnectReason.STOP
            )
        )
    }

    @Test
    fun `a disconnect with no recorded reason keeps the host`() {
        assertEquals(
            ReconnectPolicy.RECONNECT_KEEP_HOST,
            reconnectPolicyFor(WebSocketConnectionState.DISCONNECTED)
        )
    }

    @Test
    fun `a server close wins over a stale local reason`() {
        assertEquals(
            ReconnectPolicy.RECONNECT_KEEP_HOST,
            reconnectPolicyFor(
                WebSocketConnectionState.DISCONNECTED,
                closedByServer = true,
                localDisconnectReason = LocalDisconnectReason.HEARTBEAT_LOST
            )
        )
    }

    @Test
    fun `every local disconnect reason has a deliberate host decision`() {
        val switching = LocalDisconnectReason.entries.filter { it.switchesHost }

        assertEquals(
            listOf(LocalDisconnectReason.CONNECT_TIMEOUT, LocalDisconnectReason.HEARTBEAT_LOST),
            switching
        )
    }
}
