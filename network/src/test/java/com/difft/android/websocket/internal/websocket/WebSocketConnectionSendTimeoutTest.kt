package com.difft.android.websocket.internal.websocket

import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import com.difft.android.websocket.api.websocket.WebSocketResponseTimeoutException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Test
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketRequestMessage
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue






@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketConnectionSendTimeoutTest {

    private fun newConnection(): WebSocketConnection = WebSocketConnection(
        auth = { "auth" },
        webSocketUrlGetter = { "wss://example.invalid" },
        keepAliveSender = mockk(relaxed = true),
        userAgent = "test",
        healthMonitor = mockk(relaxed = true),
        context = mockk(relaxed = true),
        proxyConfigProvider = mockk(relaxed = true),
    )

    private fun WebSocketConnection.setPrivate(field: String, value: Any?) {
        val f = WebSocketConnection::class.java.getDeclaredField(field)
        f.isAccessible = true
        f.set(this, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun WebSocketConnection.outgoing(): MutableMap<Long, *> {
        val f = WebSocketConnection::class.java.getDeclaredField("outgoingRequests")
        f.isAccessible = true
        return f.get(this) as MutableMap<Long, *>
    }

    @Suppress("UNCHECKED_CAST")
    private fun WebSocketConnection.markConnected(socket: WebSocket) {
        val f = WebSocketConnection::class.java.getDeclaredField("_webSocketConnectionState")
        f.isAccessible = true
        (f.get(this) as MutableSharedFlow<WebSocketConnectionState>).tryEmit(WebSocketConnectionState.CONNECTED)
        setPrivate("currentWebsocket", socket)
    }

    private fun request(id: Long = 7L): WebSocketRequestMessage =
        WebSocketRequestMessage.newBuilder().setRequestId(id).setVerb("PUT").setPath("/v4/messages/x").build()

    @Test
    fun `sendRequestAwaitResponse throws WebSocketResponseTimeoutException after 10s and removes pending request`() = runTest {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<ByteString>()) } returns true
        val connection = newConnection()
        connection.markConnected(socket)

        val deferred = async {
            runCatching { connection.sendRequestAwaitResponse(request()) }.exceptionOrNull()
        }
        runCurrent()
        assertEquals(1, connection.outgoing().size, "request is pending while waiting")

        advanceTimeBy(10_001)
        runCurrent()

        val thrown = deferred.await()
        assertTrue(thrown is WebSocketResponseTimeoutException, "was $thrown")
        assertTrue(thrown is IOException)
        assertEquals(0, connection.outgoing().size, "pending request removed after timeout")
    }

    @Test
    fun `sendRequestAwaitResponse fails with IOException when write fails and removes pending request`() = runTest {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<ByteString>()) } returns false
        val connection = newConnection()
        connection.markConnected(socket)

        assertFailsWith<IOException> { connection.sendRequestAwaitResponse(request()) }
        assertEquals(0, connection.outgoing().size)
    }
}
