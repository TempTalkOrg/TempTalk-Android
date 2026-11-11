package com.difft.android.websocket.api.services

import com.difft.android.base.session.AuthFailureSink
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.internal.push.NewOutgoingPushMessage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue





class NewMessagingServiceCancellationTest {

    private val appWebSocketHelper: AppWebSocketHelper = mockk()
    private val service = NewMessagingService(appWebSocketHelper, dagger.Lazy { mockk<AuthFailureSink>(relaxed = true) })

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

    @Test
    fun `cancelling the caller while awaiting the response propagates instead of returning`() = runTest {
        coEvery { appWebSocketHelper.sendChatMessage(any()) } coAnswers { awaitCancellation() }
        var returned = false

        val job = launch {
            service.send(message(), "user-123")
            returned = true
        }
        runCurrent()
        job.cancel()
        job.join()

        assertFalse(returned)
    }

    @Test
    fun `a stray cancellation while the caller is active becomes an execution error`() = runTest {
        coEvery { appWebSocketHelper.sendChatMessage(any()) } throws CancellationException("stray")

        val response = service.send(message(), "user-123")

        assertTrue(response.executionError.isPresent)
    }
}
