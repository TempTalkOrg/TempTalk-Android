package com.difft.android.chat.jobs

import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.push.exceptions.NoValidRecipientKeysException
import com.difft.android.websocket.api.push.exceptions.ServerRejectedException
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.io.IOException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * issue #970 ②: failed-group orphan receipt churn — permanent-vs-transient retry contract.
 *
 * Covers:
 * - T8/T9/T10: [PushSendJob.onShouldRetry] classification (the single chokepoint all 3 send
 *   jobs share). NoValidRecipientKeysException → false (permanent, stop churn); IOException →
 *   true (transient, weak-net keeps retrying); ServerRejectedException → false (pre-existing).
 * - T13: [PushReadReceiptSendJob.run] with a permanent exception → job exits the queue
 *   (onPushSend catch does NOT rethrow because onShouldRetry=false → BaseJob.run → success).
 * - T14 (regression anchor): same job with an IOException → Result.retry (weak net still
 *   retries — must NOT be killed by the #970 fix).
 *
 * T15 (PushTextSendJob → markSendFailed on permanent exception) is covered at the decision
 * point by T8: PushTextSendJob.onPushSend line `if (onShouldRetry(e)) throw e else markSendFailed()`
 * routes a NoValidRecipientKeysException to markSendFailed precisely because onShouldRetry=false.
 * The full PushTextSendJob path is WCDB-heavy (markSendFailed writes the DB); asserting the shared
 * onShouldRetry contract is the production decision, not a re-implementation of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushSendJobRetryTest {

    /** Minimal PushSendJob subclass to reach the protected onShouldRetry. */
    private class ProbeJob : PushSendJob(buildParams()) {
        public override suspend fun onPushSend() = Unit
        override fun serialize() = com.difft.android.chat.jobmanager.Data.Builder().build()
        override fun getFactoryKey() = "ProbeJob"
        override fun onAdded() = Unit
        override fun onFailure() = Unit
        fun shouldRetry(e: Exception): Boolean = onShouldRetry(e)

        companion object {
            fun buildParams() = Parameters.Builder().setQueue("probe").build()
        }
    }

    private lateinit var messageSender: NewSignalServiceMessageSender
    private val gson = Gson()

    @Before
    fun setUp() {
        messageSender = mockk()
        // PushReadReceiptSendJob does not read globalServices in onPushSend, but other
        // PushSendJob construction paths might; mock defensively.
        val mockGlobal = mockk<GlobalHiltEntryPoint>(relaxed = true)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockGlobal
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -----------------------------------------------------------------
    // T8/T9/T10: onShouldRetry classification (shared chokepoint)
    // -----------------------------------------------------------------

    /** T8: permanent exception → false (stop churn). The crux — must NOT be an IOException. */
    @Test
    fun `onShouldRetry returns false for NoValidRecipientKeysException`() {
        val e = NoValidRecipientKeysException("no keys")
        // Reflective check (not `is`) so the compiler can't fold it — this is the crux guard.
        assertFalse(
            IOException::class.java.isAssignableFrom(e.javaClass),
            "NoValidRecipientKeysException must NOT be an IOException"
        )
        assertFalse(ProbeJob().shouldRetry(e))
    }

    /** T9: weak-net IOException → true (keep retrying, no false kill). */
    @Test
    fun `onShouldRetry returns true for IOException`() {
        assertTrue(ProbeJob().shouldRetry(IOException("network down")))
    }

    /** T10: pre-existing ServerRejectedException behavior preserved. */
    @Test
    fun `onShouldRetry returns false for ServerRejectedException`() {
        assertFalse(ProbeJob().shouldRetry(ServerRejectedException()))
    }

    // -----------------------------------------------------------------
    // T13/T14: PushReadReceiptSendJob.run end-to-end retry decision
    // -----------------------------------------------------------------

    private fun buildReadReceiptJob(): PushReadReceiptSendJob {
        val readPosition = SignalServiceProtos.ReadPosition.newBuilder()
            .setGroupId(com.google.protobuf.ByteString.copyFromUtf8("g-invalid"))
            .build()
        return PushReadReceiptSendJob(
            parameters = null,
            recipientId = "+recipient",
            messageSentTimestamps = listOf(1000L),
            readPosition1 = readPosition,
            mode = SignalServiceProtos.Mode.NORMAL,
            conversationId = "g-invalid",
            sendReceiptToSender = true,
            sendSyncToSelf = false,
            newSignalServiceMessageSender = messageSender,
            gson = gson,
        )
    }

    /** T13: permanent exception from sender → job exits queue (success, NOT retry). */
    @Test
    fun `read receipt job exits queue on permanent exception`() = runTest {
        coEvery {
            messageSender.sendReceipt(any(), any(), any(), any(), any(), any())
        } throws NoValidRecipientKeysException("invalid group, no keys")

        val result = buildReadReceiptJob().run()

        // onPushSend catch swallows it (onShouldRetry=false → no rethrow) → BaseJob → success.
        // Either way the job leaves the queue: it is NOT a retry. This stops the #970 ② churn.
        assertFalse(result.isRetry(), "permanent failure must NOT schedule a retry")
        assertTrue(result.isSuccess(), "swallowed permanent failure → BaseJob.run returns success")
    }

    /** T14 (regression anchor): weak-net IOException → Result.retry (must keep retrying). */
    @Test
    fun `read receipt job retries on transient IOException`() = runTest {
        coEvery {
            messageSender.sendReceipt(any(), any(), any(), any(), any(), any())
        } throws IOException("ws down")

        val result = buildReadReceiptJob().run()

        // onShouldRetry=true → onPushSend rethrows → BaseJob.run → Result.retry.
        assertTrue(result.isRetry(), "weak-net failure must keep retrying — not killed by #970 fix")
    }
}
