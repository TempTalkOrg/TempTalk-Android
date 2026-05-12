package com.difft.android.websocket.api

import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.network.signal.MessageSendRepository
import com.difft.android.websocket.api.services.NewMessagingService
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import com.google.protobuf.ByteString
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptMessage
import org.whispersystems.signalservice.internal.push.content
import org.whispersystems.signalservice.internal.push.conversationId
import org.whispersystems.signalservice.internal.push.forwardNoticeMessage
import org.whispersystems.signalservice.internal.push.notifyMessage

/**
 * Proto-layer contract tests for ForwardNoticeMessage + its self-sync wrapper.
 *
 * v3 design:
 *   - Primary : `Content.forwardNotice = ForwardNoticeMessage`
 *   - Sync    : `Content.syncMessage.forwardNoticeSync = ForwardNoticeMessage`
 *              (direct reference; no wrapper type — v1's destination field was
 *               redundant with ForwardNoticeMessage.conversation.number)
 *
 * ForwardNoticeMessage.conversation (tag 4) is self-contained, so sync doesn't
 * need destination; receiver reads peer from inner conversation directly.
 */
class SendForwardNoticeMessageSenderTest {

    private val messagingService: NewMessagingService = mockk(relaxed = true)
    private val messageEncryptor: INewMessageContentEncryptor = mockk(relaxed = true)
    private val conversationManager: ConversationManager = mockk(relaxed = true)
    private val messageSendRepository: MessageSendRepository = mockk(relaxed = true)

    private lateinit var sender: NewSignalServiceMessageSender

    @Before
    fun setUp() {
        val mockGlobal = mockk<GlobalHiltEntryPoint>(relaxed = true)
        every { mockGlobal.myId } returns "MY_ID"
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockGlobal

        sender = NewSignalServiceMessageSender(
            messagingService = messagingService,
            maxEnvelopeSize = 0L,
            messageEncryptor = messageEncryptor,
            conversationManager = conversationManager,
            messageSendRepository = messageSendRepository,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `content forwardNotice carries scene authors count and payload conversation groupId`() {
        val groupIdAscii = "g".repeat(32)
        val proto = forwardNoticeMessage {
            scene = SignalServiceProtos.ForwardNoticeMessage.ForwardScene.COMBINED
            sourceAuthorIds.add("+10001")
            sourceAuthorIds.add("+10002")
            messageCount = 3
            conversation = conversationId {
                groupId = ByteString.copyFromUtf8(groupIdAscii)
            }
        }

        val primary: Content = content { forwardNotice = proto }

        assertTrue(primary.hasForwardNotice())
        assertEquals(3, primary.forwardNotice.messageCount)
        assertTrue(primary.forwardNotice.hasConversation())
        assertTrue(primary.forwardNotice.conversation.hasGroupId())
        assertEquals(groupIdAscii, primary.forwardNotice.conversation.groupId.toStringUtf8())
    }

    @Test
    fun `content forwardNotice carries payload conversation number for 1v1 source`() {
        val proto = forwardNoticeMessage {
            scene = SignalServiceProtos.ForwardNoticeMessage.ForwardScene.SINGLE
            sourceAuthorIds.add("+30001")
            messageCount = 1
            conversation = conversationId { number = "+peer-99" }
        }

        val primary: Content = content { forwardNotice = proto }

        assertTrue(primary.hasForwardNotice())
        assertTrue(primary.forwardNotice.hasConversation())
        assertTrue(primary.forwardNotice.conversation.hasNumber())
        assertEquals("+peer-99", primary.forwardNotice.conversation.number)
        assertFalse(primary.forwardNotice.conversation.hasGroupId())
    }

    @Test
    fun `primary forwardNotice content does not cross-contaminate other Content oneof branches`() {
        val primary = content {
            forwardNotice = forwardNoticeMessage {
                scene = SignalServiceProtos.ForwardNoticeMessage.ForwardScene.ONE_BY_ONE
                messageCount = 2
                sourceAuthorIds.add("+1")
            }
        }

        assertTrue(primary.hasForwardNotice())
        assertFalse(primary.hasSyncMessage())
        assertFalse(primary.hasDataMessage())
        assertFalse(primary.hasReceiptMessage())
        assertFalse(primary.hasNotifyMessage())
        assertFalse(primary.hasGroupKeyMessage())
    }

    @Test
    fun `non-forward-notice content does not report hasForwardNotice`() {
        val dataOnly = content {
            dataMessage = SignalServiceProtos.DataMessage.newBuilder()
                .setBody("hello")
                .build()
        }
        assertFalse(dataOnly.hasForwardNotice())

        val receiptOnly = content {
            receiptMessage = ReceiptMessage.newBuilder()
                .setType(ReceiptMessage.Type.READ)
                .build()
        }
        assertFalse(receiptOnly.hasForwardNotice())

        val notifyOnly = content { notifyMessage = notifyMessage { } }
        assertFalse(notifyOnly.hasForwardNotice())
    }

    // -----------------------------------------------------------------
    // Sync builder: `createMultiDeviceForwardNoticeContent` wraps
    // ForwardNoticeMessage in Content.syncMessage.forwardNoticeSync.
    // Anti-regression: body MUST NOT be empty (same class of bug as
    // difft-android's createMultiDeviceNotifyContent which set the body
    // in an `if` block but forgot to assign back to syncMessage).
    // -----------------------------------------------------------------
    @Test
    fun `createMultiDeviceForwardNoticeContent wraps in SyncMessage forwardNoticeSync`() {
        val proto = forwardNoticeMessage {
            scene = SignalServiceProtos.ForwardNoticeMessage.ForwardScene.COMBINED
            sourceAuthorIds.add("+10001")
            messageCount = 2
            conversation = conversationId { number = "+peer-uid" }
        }

        val syncContent = sender.createMultiDeviceForwardNoticeContent(proto)

        assertTrue(syncContent.hasSyncMessage())
        assertTrue(syncContent.syncMessage.hasForwardNoticeSync())
        assertFalse(
            "Sync content must NOT set primary hasForwardNotice — different oneof branches",
            syncContent.hasForwardNotice()
        )
        // Inner payload preserved end-to-end.
        val inner = syncContent.syncMessage.forwardNoticeSync
        assertEquals(2, inner.messageCount)
        assertEquals("+peer-uid", inner.conversation.number)
        assertEquals(
            SignalServiceProtos.ForwardNoticeMessage.ForwardScene.COMBINED,
            inner.scene
        )
    }

    @Test
    fun `MSG_FORWARD_NOTICE_VALUE proto constant is 14`() {
        assertEquals(14, Envelope.MsgType.MSG_FORWARD_NOTICE_VALUE)
        assertEquals(Envelope.MsgType.MSG_FORWARD_NOTICE, Envelope.MsgType.forNumber(14))
    }
}
