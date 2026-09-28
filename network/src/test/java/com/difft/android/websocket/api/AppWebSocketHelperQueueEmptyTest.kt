package com.difft.android.websocket.api

import com.difft.android.websocket.api.messages.ChatDataMessageFlag
import com.difft.android.websocket.internal.websocket.WebSocketConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketRequestMessage
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketResponseMessage

/**
 * Pins the wire contract of the two end-of-queue markers the server pushes per connection:
 * `queue/conversation/empty` (previews done, backlog about to start) and `queue/empty` (backlog
 * fully pushed). Both must be ACK'd and mapped to their own flag; anything else keeps returning
 * null so unknown paths stay inert.
 */
class AppWebSocketHelperQueueEmptyTest {

    private companion object {
        const val REQUEST_ID = 1331660308L
    }

    private val connection: WebSocketConnection = mockk(relaxed = true)
    private val helper = AppWebSocketHelper(connection)

    private fun request(path: String, verb: String = "PUT"): WebSocketRequestMessage =
        WebSocketRequestMessage.newBuilder().setVerb(verb).setPath(path).setRequestId(REQUEST_ID).build()

    @Test
    fun `queue empty maps to END_QUEUE and is acknowledged`() {
        every { connection.readRequest() } returns request("/api/v1/queue/empty")
        val response = slot<WebSocketResponseMessage>()

        val result = helper.readOrEmptyFromChatDataWebSocket()

        assertEquals(REQUEST_ID, result?.first)
        assertEquals(ChatDataMessageFlag.END_QUEUE, result?.second)
        verify(exactly = 1) { connection.sendResponse(capture(response)) }
        assertEquals(REQUEST_ID, response.captured.requestId)
        assertEquals(200, response.captured.status)
    }

    @Test
    fun `conversation queue empty still maps to END_CONVERSATION_PREVIEW_MGS`() {
        every { connection.readRequest() } returns request("/api/v1/queue/conversation/empty")

        val result = helper.readOrEmptyFromChatDataWebSocket()

        assertEquals(ChatDataMessageFlag.END_CONVERSATION_PREVIEW_MGS, result?.second)
    }

    @Test
    fun `an unknown path is still acknowledged and returns null`() {
        every { connection.readRequest() } returns request("/api/v1/something/new")

        val result = helper.readOrEmptyFromChatDataWebSocket()

        assertEquals(null, result)
        verify(exactly = 1) { connection.sendResponse(any()) }
    }

    @Test
    fun `a GET on the queue empty path is not treated as the marker`() {
        every { connection.readRequest() } returns request("/api/v1/queue/empty", verb = "GET")

        val result = helper.readOrEmptyFromChatDataWebSocket()

        assertEquals(null, result)
    }
}
