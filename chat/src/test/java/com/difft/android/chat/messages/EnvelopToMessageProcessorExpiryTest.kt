package com.difft.android.chat.messages

import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.chat.group.GroupUtil
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import difft.android.messageserialization.For
import difft.android.messageserialization.model.TextMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import kotlin.test.assertIs

class EnvelopToMessageProcessorExpiryTest {
    private val decrypt = mockk<NewMessageDecryptionUtil>()
    private val content = mockk<MessageContentProcessor>()
    private val store = mockk<DBMessageStore>()
    private val groupUtil = mockk<GroupUtil>(relaxed = true)
    private val processor = EnvelopToMessageProcessor(
        decrypt, content, store, mockk(relaxed = true), mockk(relaxed = true), groupUtil,
    )
    private val envelope = SignalServiceProtos.Envelope.newBuilder().setTimestamp(1).build()
    private val admission = requireNotNull(TestMessagingPause().open().gate.tryAdmit(AdmissionKind.RECEIVE, "test"))

    @Test
    fun `newly persisted incoming group item clears expiry`() = runTest {
        arrange(dataContent(), For.Group("group"))
        assertIs<EnvelopeProcessResult.Success>(processor.process(envelope, "message", admission))
        coVerify(exactly = 1) { groupUtil.clearExpiry("group", "incoming") }
    }

    @Test
    fun `visible sent sync from another device clears expiry as sent`() = runTest {
        val sync = SignalServiceProtos.SyncMessage.newBuilder().setSent(
            SignalServiceProtos.SyncMessage.Sent.newBuilder().setMessage(data()),
        )
        arrange(SignalServiceProtos.Content.newBuilder().setSyncMessage(sync).build(), For.Group("group"))
        assertIs<EnvelopeProcessResult.Success>(processor.process(envelope, "message", admission))
        coVerify(exactly = 1) { groupUtil.clearExpiry("group", "sent") }
    }

    @Test
    fun `own group message delivered as normal data clears expiry as sent`() = runTest {
        arrange(dataContent(), For.Group("group"), sender = "me")
        assertIs<EnvelopeProcessResult.Success>(processor.process(envelope, "message", admission))
        coVerify(exactly = 1) { groupUtil.clearExpiry("group", "sent") }
    }

    @Test
    fun `duplicate and direct messages do not clear expiry`() = runTest {
        arrange(dataContent(), For.Group("group"), inserted = 0)
        assertIs<EnvelopeProcessResult.Success>(processor.process(envelope, "message", admission))
        arrange(dataContent(), For.Account("peer"))
        assertIs<EnvelopeProcessResult.Success>(processor.process(envelope, "message", admission))
        coVerify(exactly = 0) { groupUtil.clearExpiry(any(), any()) }
    }

    @Test
    fun `a failed persistence does not clear expiry`() = runTest {
        arrange(dataContent(), For.Group("group"))
        every { store.putWhenNonExist(any()) } throws IllegalStateException("disk full")
        assertIs<EnvelopeProcessResult.TransientFailure>(processor.process(envelope, "message", admission))
        coVerify(exactly = 0) { groupUtil.clearExpiry(any(), any()) }
    }

    @Test
    fun `receipts and content without a visible item do not clear expiry`() = runTest {
        for (wire in listOf(
            SignalServiceProtos.Content.newBuilder().setReceiptMessage(SignalServiceProtos.ReceiptMessage.getDefaultInstance()).build(),
            SignalServiceProtos.Content.getDefaultInstance(),
        )) {
            arrange(wire, For.Group("group"))
            coEvery { content.process(any(), any(), any()) } returns null
            assertIs<EnvelopeProcessResult.Success>(processor.process(envelope, "message", admission))
        }
        coVerify(exactly = 0) { groupUtil.clearExpiry(any(), any()) }
    }

    @Test
    fun `recall and reaction control payloads never clear even when represented by unsupported item`() = runTest {
        for (wire in listOf(
            data().toBuilder().setRecall(SignalServiceProtos.DataMessage.Recall.getDefaultInstance()).build(),
            data().toBuilder().setReaction(SignalServiceProtos.DataMessage.Reaction.getDefaultInstance()).build(),
        )) {
            arrange(SignalServiceProtos.Content.newBuilder().setDataMessage(wire).build(), For.Group("group"))
            assertIs<EnvelopeProcessResult.Success>(processor.process(envelope, "message", admission))
        }
        coVerify(exactly = 0) { groupUtil.clearExpiry(any(), any()) }
    }

    private fun arrange(wire: SignalServiceProtos.Content, conversation: For, inserted: Int = 1, sender: String = "peer") {
        val wrapper = mockk<SignalServiceDataClass> {
            every { signalServiceContent } returns wire
            every { this@mockk.conversation } returns conversation
            every { shouldShowNotification } returns false
            every { senderId } returns sender
            every { myId } returns "me"
        }
        val item = mockk<TextMessage>(relaxed = true)
        every { decrypt.decrypt(envelope) } returns wrapper
        coEvery { content.process(wrapper, any(), any()) } returns item
        every { store.putWhenNonExist(any()) } returns inserted
    }

    private fun data() = SignalServiceProtos.DataMessage.newBuilder().setBody("text").build()
    private fun dataContent() = SignalServiceProtos.Content.newBuilder().setDataMessage(data()).build()
}
