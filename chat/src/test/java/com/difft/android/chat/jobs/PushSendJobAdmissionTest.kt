package com.difft.android.chat.jobs

import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.google.gson.Gson
import com.google.protobuf.ByteString
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Send admission of [PushSendJob] under the migration message pause (#1382): a send that is not
 * admitted ends the run as a deferral before any network call, and an admitted send keeps its
 * ticket open until the job runner closes it.
 */
class PushSendJobAdmissionTest {

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockk<GlobalHiltEntryPoint>(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a paused gate defers the run before the send`() = runTest {
        val job = ProbeSendJob(TestMessagingPause().open().pauseMessaging().gate)

        val result = job.run()

        assertTrue(result.isDeferred(), "expected deferred, was $result")
        assertEquals(0, job.sends)
    }

    @Test
    fun `a gate still loading defers the run before the send`() = runTest {
        val job = ProbeSendJob(TestMessagingPause().gate)

        val result = job.run()

        assertTrue(result.isDeferred(), "expected deferred, was $result")
        assertEquals(0, job.sends)
    }

    @Test
    fun `an admitted send keeps its ticket open until the runner closes it`() = runTest {
        val job = ProbeSendJob(TestMessagingPause().open().gate)

        val result = job.run()

        assertTrue(result.isSuccess())
        val ticket = assertNotNull(job.lastTicket)
        assertFalse(ticket.isClosed, "the ticket must outlive run() to cover the result write")
        job.closeRunResources()
        assertTrue(ticket.isClosed)
    }

    @Test
    fun `a deferral does not carry over to the next run`() = runTest {
        val pause = TestMessagingPause().open().pauseMessaging()
        val job = ProbeSendJob(pause.gate)
        assertTrue(job.run().isDeferred())

        pause.resumeMessaging()

        assertTrue(job.run().isSuccess())
        assertEquals(1, job.sends)
        job.closeRunResources()
    }

    /** The receipt job's catch-all rethrows; a refusal must bypass it and still be a deferral. */
    @Test
    fun `a job with a catch-all around its send is deferred without calling the sender`() = runTest {
        val sender = mockk<NewSignalServiceMessageSender>()
        val job = PushReadReceiptSendJob(
            parameters = null,
            recipientId = "+recipient",
            messageSentTimestamps = listOf(1000L),
            readPosition1 = SignalServiceProtos.ReadPosition.newBuilder()
                .setGroupId(ByteString.copyFromUtf8("g")).build(),
            mode = SignalServiceProtos.Mode.NORMAL,
            conversationId = "g",
            sendReceiptToSender = true,
            sendSyncToSelf = false,
            newSignalServiceMessageSender = sender,
            gson = Gson(),
            messagingGate = TestMessagingPause().open().pauseMessaging().gate,
        ).bindLogin()

        val result = job.run()

        assertTrue(result.isDeferred(), "expected deferred, was $result")
        coVerify(exactly = 0) { sender.sendReceipt(any(), any(), any(), any(), any(), any(), any()) }
    }
}
