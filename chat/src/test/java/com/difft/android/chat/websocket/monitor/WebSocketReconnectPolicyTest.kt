package com.difft.android.chat.websocket.monitor

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
            reconnectPolicyFor(WebSocketConnectionState.DISCONNECTED)
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
}
