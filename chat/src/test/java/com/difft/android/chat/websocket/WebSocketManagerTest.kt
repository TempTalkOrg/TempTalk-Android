package com.difft.android.chat.websocket

import com.difft.android.chat.messages.IncomingMessageObserver
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.api.websocket.LocalDisconnectReason
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import com.difft.android.websocket.internal.websocket.WebSocketConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [WebSocketManager.reconnectAfterProxyChange] — the two-branch
 * matrix added in round 2 of the proxy-toggle reconnect refinement.
 *
 * Why these tests exist:
 * - **Finding 1 (HIGH)**: the original implementation only cancelled when
 *   `state == CONNECTED`. A proxy toggle during the in-flight `CONNECTING`
 *   handshake would silently complete with the OLD proxy state. The fix
 *   extends cancellation to cover `CONNECTING`; `DISCONNECTED` (and other
 *   terminal-failure states) remain a no-op so we don't fight healthMonitor's
 *   own reconnect attempts.
 * - **Finding 2 (MEDIUM)**: defense-in-depth for OkHttp's `ConnectionPool` —
 *   the proxy-toggle path now calls `evictConnectionPool()` BEFORE
 *   `cancelConnection()` so any pooled idle socket built against the
 *   pre-toggle proxy state cannot be reused on the next `newWebSocket()`.
 *
 * Test surface: [WebSocketManager.reconnectAfterProxyChange] plus the [SessionAuthGuard]
 * lifetime binding added for issue #1230 (the guard must live exactly as long as the socket).
 */
class WebSocketManagerTest {

    private lateinit var appWebSocketHelper: AppWebSocketHelper
    private lateinit var incomingMessageObserver: IncomingMessageObserver
    private lateinit var conn: WebSocketConnection
    private lateinit var sessionAuthGuard: SessionAuthGuard
    private lateinit var manager: WebSocketManager

    /**
     * Real `MutableSharedFlow(replay = 1)` mirrors the production type
     * exposed by [WebSocketConnection.webSocketConnectionState]. The `.value`
     * extension property in `WebSocketConnection.kt` reads `replayCache.lastOrNull()`,
     * so emitting into this flow drives the branch decision.
     */
    private lateinit var stateFlow: MutableSharedFlow<WebSocketConnectionState>

    @Before
    fun setUp() {
        stateFlow = MutableSharedFlow(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        conn = mockk(relaxed = true)
        every { conn.webSocketConnectionState } returns stateFlow

        appWebSocketHelper = mockk(relaxed = true)
        every { appWebSocketHelper.chatDataWebSocketConnection } returns conn

        incomingMessageObserver = mockk(relaxed = true)
        sessionAuthGuard = mockk(relaxed = true)

        manager = WebSocketManager(appWebSocketHelper, incomingMessageObserver, sessionAuthGuard, mockk(relaxed = true))
        // The socket starts paused until messaging opens (#1382); these cases run with it open.
        manager.resume()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- SessionAuthGuard lifetime binding (issue #1230) ---

    @Test
    fun `start arms the session auth guard once, duplicate start does not re-arm`() {
        manager.start()
        manager.start() // isStarted guard returns early

        verify(exactly = 1) { sessionAuthGuard.start() }
    }

    @Test
    fun `stop disarms the session auth guard`() {
        manager.start()
        manager.stop()

        verify(exactly = 1) { sessionAuthGuard.stop() }
    }

    // --- Migration message pause, G-R1 (#1382) ---

    @Test
    fun `a manager started while messaging is closed starts the observer and guard but never monitors`() {
        val closed = WebSocketManager(appWebSocketHelper, incomingMessageObserver, sessionAuthGuard, mockk(relaxed = true))

        closed.start()

        verify(exactly = 1) { incomingMessageObserver.start() }
        verify(exactly = 1) { sessionAuthGuard.start() }
        verify(exactly = 0) { conn.startMonitoring() }
        assertTrue(closed.isSocketPaused())
    }

    @Test
    fun `pause closes the socket, drops unread frames and keeps the observer and guard`() {
        manager.start()

        manager.pause()

        verifyOrder {
            conn.stopMonitoring()
            conn.cancelConnection(LocalDisconnectReason.STOP)
            conn.clearIncomingRequests()
        }
        verify(exactly = 0) { incomingMessageObserver.stop() }
        verify(exactly = 0) { sessionAuthGuard.stop() }
    }

    @Test
    fun `T3 resume restarts monitoring only, never the observer`() {
        manager.start()
        manager.pause()

        manager.resume()

        verify(exactly = 2) { conn.startMonitoring() }
        verify(exactly = 1) { incomingMessageObserver.start() }
        verify(exactly = 0) { incomingMessageObserver.stop() }
    }

    @Test
    fun `no proxy reconnect while paused`() {
        manager.start()
        manager.pause()
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTING)

        manager.reconnectAfterProxyChange()

        verify(exactly = 0) { conn.evictConnectionPool() }
    }

    @Test
    fun `stop while paused does not touch the stopped monitor`() {
        manager.start()
        manager.pause()

        manager.stop()

        verify(exactly = 1) { conn.stopMonitoring() }
        verify(exactly = 1) { sessionAuthGuard.stop() }
    }

    // --- reconnectAfterProxyChange: not started -> no-op ---

    @Test
    fun `reconnectAfterProxyChange when not started — no-op, no pool eviction, no cancel`() {
        // manager.start() not called -> isStarted == false
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTED) // would otherwise trigger

        manager.reconnectAfterProxyChange()

        verify(exactly = 0) { conn.evictConnectionPool() }
        verify(exactly = 0) { conn.cancelConnection(any()) }
    }

    // --- reconnectAfterProxyChange: CONNECTED -> evict then cancel ---

    @Test
    fun `reconnectAfterProxyChange when state is CONNECTED — evicts pool then cancels (ordered)`() {
        manager.start()
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTED)

        manager.reconnectAfterProxyChange()

        verifyOrder {
            conn.evictConnectionPool()
            conn.cancelConnection(LocalDisconnectReason.PROXY_CHANGE)
        }
        verify(exactly = 1) { conn.evictConnectionPool() }
        verify(exactly = 1) { conn.cancelConnection(LocalDisconnectReason.PROXY_CHANGE) }
    }

    // --- reconnectAfterProxyChange: CONNECTING -> evict then cancel (THE Finding 1 case) ---

    @Test
    fun `reconnectAfterProxyChange when state is CONNECTING — evicts pool then cancels (Finding 1)`() {
        manager.start()
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTING)

        manager.reconnectAfterProxyChange()

        verifyOrder {
            conn.evictConnectionPool()
            conn.cancelConnection(LocalDisconnectReason.PROXY_CHANGE)
        }
        verify(exactly = 1) { conn.evictConnectionPool() }
        verify(exactly = 1) { conn.cancelConnection(LocalDisconnectReason.PROXY_CHANGE) }
    }

    // --- reconnectAfterProxyChange: DISCONNECTED -> no-op ---

    @Test
    fun `reconnectAfterProxyChange when state is DISCONNECTED — no-op (healthMonitor handles next attempt)`() {
        manager.start()
        stateFlow.tryEmit(WebSocketConnectionState.DISCONNECTED)

        manager.reconnectAfterProxyChange()

        verify(exactly = 0) { conn.evictConnectionPool() }
        verify(exactly = 0) { conn.cancelConnection(any()) }
    }

    // --- reconnectAfterProxyChange: FAILED -> no-op ---

    @Test
    fun `reconnectAfterProxyChange when state is FAILED — no-op (next reconnect picks up new state)`() {
        manager.start()
        stateFlow.tryEmit(WebSocketConnectionState.FAILED)

        manager.reconnectAfterProxyChange()

        verify(exactly = 0) { conn.evictConnectionPool() }
        verify(exactly = 0) { conn.cancelConnection(any()) }
    }

    // --- reconnectAfterProxyChange: UNKNOWN_HOST_FAILED -> no-op ---

    @Test
    fun `reconnectAfterProxyChange when state is UNKNOWN_HOST_FAILED — no-op`() {
        manager.start()
        stateFlow.tryEmit(WebSocketConnectionState.UNKNOWN_HOST_FAILED)

        manager.reconnectAfterProxyChange()

        verify(exactly = 0) { conn.evictConnectionPool() }
        verify(exactly = 0) { conn.cancelConnection(any()) }
    }
}
