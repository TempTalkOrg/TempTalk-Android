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







class WebSocketManagerTest {

    private lateinit var appWebSocketHelper: AppWebSocketHelper
    private lateinit var incomingMessageObserver: IncomingMessageObserver
    private lateinit var conn: WebSocketConnection
    private lateinit var sessionAuthGuard: SessionAuthGuard
    private lateinit var manager: WebSocketManager

    





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
        
        manager.resume()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    

    @Test
    fun `start arms the session auth guard once, duplicate start does not re-arm`() {
        manager.start()
        manager.start() 

        verify(exactly = 1) { sessionAuthGuard.start() }
    }

    @Test
    fun `stop disarms the session auth guard`() {
        manager.start()
        manager.stop()

        verify(exactly = 1) { sessionAuthGuard.stop() }
    }

    

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

    

    @Test
    fun `reconnectAfterProxyChange when not started — no-op, no pool eviction, no cancel`() {
        
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTED) 

        manager.reconnectAfterProxyChange()

        verify(exactly = 0) { conn.evictConnectionPool() }
        verify(exactly = 0) { conn.cancelConnection(any()) }
    }

    

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

    

    @Test
    fun `reconnectAfterProxyChange when state is DISCONNECTED — no-op (healthMonitor handles next attempt)`() {
        manager.start()
        stateFlow.tryEmit(WebSocketConnectionState.DISCONNECTED)

        manager.reconnectAfterProxyChange()

        verify(exactly = 0) { conn.evictConnectionPool() }
        verify(exactly = 0) { conn.cancelConnection(any()) }
    }

    

    @Test
    fun `reconnectAfterProxyChange when state is FAILED — no-op (next reconnect picks up new state)`() {
        manager.start()
        stateFlow.tryEmit(WebSocketConnectionState.FAILED)

        manager.reconnectAfterProxyChange()

        verify(exactly = 0) { conn.evictConnectionPool() }
        verify(exactly = 0) { conn.cancelConnection(any()) }
    }

    

    @Test
    fun `reconnectAfterProxyChange when state is UNKNOWN_HOST_FAILED — no-op`() {
        manager.start()
        stateFlow.tryEmit(WebSocketConnectionState.UNKNOWN_HOST_FAILED)

        manager.reconnectAfterProxyChange()

        verify(exactly = 0) { conn.evictConnectionPool() }
        verify(exactly = 0) { conn.cancelConnection(any()) }
    }
}
