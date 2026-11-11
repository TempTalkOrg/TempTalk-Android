package com.difft.android.chat.messages

import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.base.monitor.DeliveryChannel
import com.difft.android.base.monitor.KeepAliveHealthCounters
import com.difft.android.base.monitor.LateDeliveryTracker
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import difft.android.messageserialization.model.Message
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope






class EnvelopToMessageProcessorHealthSamplingTest {

    private val decryptionUtil = mockk<NewMessageDecryptionUtil>()
    private val contentProcessor = mockk<MessageContentProcessor>()
    private val dbMessageStore = mockk<DBMessageStore>()
    private val counters = mockk<KeepAliveHealthCounters>(relaxed = true)
    private val lateTracker = mockk<LateDeliveryTracker>(relaxed = true)
    private lateinit var processor: EnvelopToMessageProcessor
    private val admission = requireNotNull(TestMessagingPause().open().gate.tryAdmit(AdmissionKind.RECEIVE, "test"))

    @Before
    fun setUp() {
        processor = EnvelopToMessageProcessor(decryptionUtil, contentProcessor, dbMessageStore, counters, lateTracker, mockk(relaxed = true))
        every { decryptionUtil.decrypt(any()) } returns mockk<SignalServiceDataClass>(relaxed = true)
        coEvery { contentProcessor.process(any(), any(), any()) } returns mockk<Message>(relaxed = true)
    }

    private fun envelope(): Envelope = Envelope.newBuilder().setTimestamp(1L).setSystemShowTimestamp(2L).build()

    @Test
    fun `a newly stored message is sampled on its arrival channel`() = runBlocking {
        every { dbMessageStore.putWhenNonExist(any()) } returns 1
        processor.process(envelope(), "fcm", admission)
        verify(exactly = 1) { counters.onEnvelopeDelivered(2L, DeliveryChannel.FCM) }
        verify(exactly = 1) { lateTracker.onEnvelopeDelivered(2L) }
    }

    @Test
    fun `an already stored message delivered again is not sampled`() = runBlocking {
        every { dbMessageStore.putWhenNonExist(any()) } returns 0
        processor.process(envelope(), "PendingMessageHelper", admission)
        processor.process(envelope(), "message", admission)
        verify(exactly = 0) { counters.onEnvelopeDelivered(any(), any()) }
        verify(exactly = 0) { lateTracker.onEnvelopeDelivered(any()) }
    }

    @Test
    fun `a delivery from a local retry channel is not sampled`() = runBlocking {
        every { dbMessageStore.putWhenNonExist(any()) } returns 1
        processor.process(envelope(), "FailedMessageProcessor", admission)
        verify(exactly = 0) { lateTracker.onEnvelopeDelivered(any()) }
    }
}
