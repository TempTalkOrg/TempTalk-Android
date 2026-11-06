package com.difft.android.chat.jobs

import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.jobmanager.JobLoginTag
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
import kotlin.test.assertTrue

/**
 * A send job only sends for the login that created it (#1382 A04): a job of another login, or one
 * with no creator login, fails before any network work, whether or not the upgrade sweep ran; with
 * no login signed in the job waits.
 */
class PushSendJobLoginTest {

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
    fun `a job of the signed-in login sends`() = runTest {
        val job = ProbeSendJob(TestMessagingPause().open().gate)

        val result = job.run()

        assertTrue(result.isSuccess(), "expected success, was $result")
        assertEquals(1, job.sends)
        job.closeRunResources()
    }

    @Test
    fun `a job of another login fails before any work`() = runTest {
        val job = ProbeSendJob(TestMessagingPause().open().gate).bindLogin(creator = "login-b", current = TEST_LOGIN)

        val result = job.run()

        assertTrue(result.isFailure(), "expected failure, was $result")
        assertFalse(job.started, "onPushSend must not start for another login")
        assertEquals(0, job.sends)
        job.closeRunResources()
    }

    @Test
    fun `an untagged job fails before any work`() = runTest {
        val job = ProbeSendJob(TestMessagingPause().open().gate).bindLogin(creator = null, current = TEST_LOGIN)

        val result = job.run()

        assertTrue(result.isFailure(), "expected failure, was $result")
        assertFalse(job.started)
        assertEquals(0, job.sends)
        job.closeRunResources()
    }

    @Test
    fun `a login change during the run is refused at the send`() = runTest {
        var current: String? = TEST_LOGIN
        val job = ProbeSendJob(TestMessagingPause().open().gate)
        job.loginTag = JobLoginTag { current }
        job.beforeAdmit = { current = "login-b" }

        val result = job.run()

        assertTrue(result.isFailure(), "expected failure, was $result")
        assertTrue(job.started)
        assertEquals(0, job.sends)
        job.closeRunResources()
    }

    @Test
    fun `with no login signed in the job waits instead of failing`() = runTest {
        val job = ProbeSendJob(TestMessagingPause().open().gate).bindLogin(current = null)

        val result = job.run()

        assertTrue(result.isDeferred(), "expected deferred, was $result")
        assertEquals(0, job.sends)
        job.closeRunResources()
    }

    /** A real job with a catch-all around its send: the refusal still fails it without a network call. */
    @Test
    fun `a real send job of another login never calls the sender`() = runTest {
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
            messagingGate = TestMessagingPause().open().gate,
        ).bindLogin(creator = "login-b", current = TEST_LOGIN)

        val result = job.run()

        assertTrue(result.isFailure(), "expected failure, was $result")
        coVerify(exactly = 0) { sender.sendReceipt(any(), any(), any(), any(), any(), any(), any()) }
        job.closeRunResources()
    }
}
