package com.difft.android.chat.jobs

import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.test.fakes.TestMessagingPause
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







@OptIn(ExperimentalCoroutinesApi::class)
class PushSendJobRetryTest {

    
    private class ProbeJob : PushSendJob(buildParams(), TestMessagingPause().open().gate) {
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
        
        
        val mockGlobal = mockk<GlobalHiltEntryPoint>(relaxed = true)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockGlobal
    }

    @After
    fun tearDown() {
        unmockkAll()
    }
    

    
    @Test
    fun `onShouldRetry returns false for NoValidRecipientKeysException`() {
        val e = NoValidRecipientKeysException("no keys")
        
        assertFalse(
            IOException::class.java.isAssignableFrom(e.javaClass),
            "NoValidRecipientKeysException must NOT be an IOException"
        )
        assertFalse(ProbeJob().shouldRetry(e))
    }

    
    @Test
    fun `onShouldRetry returns true for IOException`() {
        assertTrue(ProbeJob().shouldRetry(IOException("network down")))
    }

    
    @Test
    fun `onShouldRetry returns false for ServerRejectedException`() {
        assertFalse(ProbeJob().shouldRetry(ServerRejectedException()))
    }
    

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
            messagingGate = TestMessagingPause().open().gate,
        ).bindLogin()
    }

    
    @Test
    fun `read receipt job exits queue on permanent exception`() = runTest {
        coEvery {
            messageSender.sendReceipt(any(), any(), any(), any(), any(), any(), any())
        } throws NoValidRecipientKeysException("invalid group, no keys")

        val result = buildReadReceiptJob().run()

        
        assertFalse(result.isRetry(), "permanent failure must NOT schedule a retry")
        assertTrue(result.isSuccess(), "swallowed permanent failure → BaseJob.run returns success")
    }

    
    @Test
    fun `read receipt job retries on transient IOException`() = runTest {
        coEvery {
            messageSender.sendReceipt(any(), any(), any(), any(), any(), any(), any())
        } throws IOException("ws down")

        val result = buildReadReceiptJob().run()

        
        assertTrue(result.isRetry(), "weak-net failure must keep retrying — not killed by #970 fix")
    }
}
