package com.difft.android.websocket.api

import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.util.EnvelopDeserializer
import com.difft.android.websocket.internal.websocket.WebSocketConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos.WebSocketRequestMessage


class AppWebSocketHelperMessagingPauseTest {

    private val connection: WebSocketConnection = mockk(relaxed = true)
    private val helper = AppWebSocketHelper(connection, TestMessagingPause().open().pauseMessaging().gate)

    private fun request(path: String): WebSocketRequestMessage =
        WebSocketRequestMessage.newBuilder().setVerb("PUT").setPath(path).setRequestId(7L).build()

    @Test
    fun `end markers and unknown frames are neither ACKed nor handed on while paused`() {
        listOf("/api/v1/queue/empty", "/api/v1/queue/conversation/empty", "/api/v1/something/new").forEach { path ->
            every { connection.readRequest() } returns request(path)

            assertEquals(null, helper.readOrEmptyFromChatDataWebSocket())
        }

        verify(exactly = 0) { connection.sendResponse(any()) }
    }

    @Test
    fun `an unparseable envelope is not ACKed while paused`() {
        mockkObject(EnvelopDeserializer)
        every { EnvelopDeserializer.deserializeFrom(any()) } returns null
        every { connection.readRequest() } returns request("/api/v1/message")

        try {
            helper.readOrEmptyFromChatDataWebSocket()
        } finally {
            unmockkObject(EnvelopDeserializer)
        }

        verify(exactly = 0) { connection.sendResponse(any()) }
    }
}
