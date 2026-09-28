package com.difft.android.websocket.api.services

import com.difft.android.base.session.AuthFailureSink
import com.difft.android.base.session.AuthFailureSource
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.internal.push.NewOutgoingPushMessage
import com.difft.android.websocket.internal.websocket.WebsocketResponse
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the in-socket auth hook in [NewMessagingService.innerSend] (issue #1230).
 *
 * Why this hook exists: `AuthorizationFailedException` extends `IOException`, so the caller
 * (`NewSignalServiceMessageSender`) swallows it and falls back to HTTP. If that fallback then fails
 * with a plain network error the rejection would never be reported, so it is reported here — before
 * the fallback can mask it.
 */
class NewMessagingServiceAuthHookTest {

    private val appWebSocketHelper: AppWebSocketHelper = mockk()

    /** Records the source tags; the sink is fire-and-forget so there is nothing to stub. */
    private class RecordingSink : AuthFailureSink {
        val sources = mutableListOf<String>()
        override fun onAuthRejected(source: String) {
            sources += source
        }
    }

    private lateinit var sink: RecordingSink
    private lateinit var service: NewMessagingService

    @Before
    fun setUp() {
        sink = RecordingSink()
        service = NewMessagingService(appWebSocketHelper, dagger.Lazy { sink })
    }

    @After
    fun tearDown() {
        clearMocks(appWebSocketHelper)
    }

    private fun message() = NewOutgoingPushMessage(
        type = 1,
        destination = "user-123",
        destinationDeviceId = 1,
        destinationRegistrationId = 1,
        content = "ciphertext",
        readReceipt = false,
        notification = null,
        conversation = null,
        msgType = 1,
        detailMessageType = 0,
        readPositions = null,
        realSource = null,
        silent = false,
        timestamp = 1_700_000_000_000L,
        recipients = emptyList(),
    )

    private fun respondWith(status: Int, body: String = "") {
        coEvery { appWebSocketHelper.sendChatMessage(any()) } returns
            WebsocketResponse(status, body, emptyList())
    }

    @Test
    fun `an in-socket 401 reports the auth failure exactly once`() = runTest {
        respondWith(401)

        service.send(message(), "user-123")

        assertEquals(listOf(AuthFailureSource.WS_RESPONSE), sink.sources)
    }

    @Test
    fun `an in-socket 403 reports the auth failure exactly once`() = runTest {
        respondWith(403)

        service.send(message(), "user-123")

        assertEquals(listOf(AuthFailureSource.WS_RESPONSE), sink.sources)
    }

    @Test
    fun `a successful send reports nothing`() = runTest {
        respondWith(200)

        service.send(message(), "user-123")

        assertTrue(sink.sources.isEmpty())
    }

    @Test
    fun `a non-auth error reports nothing`() = runTest {
        respondWith(404)

        service.send(message(), "user-123")

        assertTrue(sink.sources.isEmpty())
    }
}
