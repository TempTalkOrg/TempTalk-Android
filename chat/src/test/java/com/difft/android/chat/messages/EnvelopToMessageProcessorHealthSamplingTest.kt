package com.difft.android.chat.messages

import com.difft.android.base.monitor.DeliveryChannel
import com.difft.android.base.monitor.KeepAliveHealthCounters
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

/**
 * #1328: latency is sampled only for a message that was newly stored. A redelivery of an already
 * stored envelope (e.g. FCM payload stored, process killed, same envelope pulled later) would
 * otherwise be counted again with latency measured from its original server time.
 */
class EnvelopToMessageProcessorHealthSamplingTest {

    private val decryptionUtil = mockk<NewMessageDecryptionUtil>()
    private val contentProcessor = mockk<MessageContentProcessor>()
    private val dbMessageStore = mockk<DBMessageStore>()
    private val counters = mockk<KeepAliveHealthCounters>(relaxed = true)
    private lateinit var processor: EnvelopToMessageProcessor

    @Before
    fun setUp() {
        processor = EnvelopToMessageProcessor(decryptionUtil, contentProcessor, dbMessageStore, counters)
        every { decryptionUtil.decrypt(any()) } returns mockk<SignalServiceDataClass>(relaxed = true)
        coEvery { contentProcessor.process(any(), any()) } returns mockk<Message>(relaxed = true)
    }

    private fun envelope(): Envelope = Envelope.newBuilder().setTimestamp(1L).setSystemShowTimestamp(2L).build()

    @Test
    fun `a newly stored message is sampled on its arrival channel`() = runBlocking {
        every { dbMessageStore.putWhenNonExist(any()) } returns 1
        processor.process(envelope(), "fcm")
        verify(exactly = 1) { counters.onEnvelopeDelivered(2L, DeliveryChannel.FCM) }
    }

    @Test
    fun `an already stored message delivered again is not sampled`() = runBlocking {
        every { dbMessageStore.putWhenNonExist(any()) } returns 0
        processor.process(envelope(), "PendingMessageHelper")
        processor.process(envelope(), "message")
        verify(exactly = 0) { counters.onEnvelopeDelivered(any(), any()) }
    }
}
