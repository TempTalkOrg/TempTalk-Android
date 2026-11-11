package com.difft.android.websocket.api.messages

import com.google.protobuf.ByteString
import difft.android.messageserialization.For
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ConversationId
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ForwardNoticeMessage
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncMessage


















class SignalServiceDataClassConversationTest {

    private val myId = "+10000000"
    private val peerAccount = "+15551234"
    private val otherPeer = "+15559999"
    private val groupIdString = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" 

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        val globalServicesMock = mockk<com.difft.android.base.utils.GlobalHiltEntryPoint>(relaxed = true)
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.myId } returns myId
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    
    
    
    @Test
    fun `forwardNotice payload groupId resolves to For_Group regardless of envelope_source`() {
        
        val envelope = envelopeBuilder(peerAccount).build()
        val notice = ForwardNoticeMessage.newBuilder()
            .setConversation(
                ConversationId.newBuilder()
                    .setGroupId(ByteString.copyFromUtf8(groupIdString))
                    .build()
            )
            .build()
        val content = Content.newBuilder().setForwardNotice(notice).build()

        val data = SignalServiceDataClass(envelope, content, null)
        val conv = data.conversation

        assertTrue("group forwardNotice must resolve to For.Group", conv is For.Group)
        assertEquals(groupIdString, conv.id)
    }

    
    
    
    @Test
    fun `forwardNotice primary 1v1 resolves to For_Account senderId`() {
        val envelope = envelopeBuilder(peerAccount).build()
        val notice = ForwardNoticeMessage.newBuilder().build()
        val content = Content.newBuilder().setForwardNotice(notice).build()

        val data = SignalServiceDataClass(envelope, content, null)
        val conv = data.conversation

        assertTrue(conv is For.Account)
        assertEquals(peerAccount, conv.id)
    }

    
    
    
    @Test
    fun `syncMessage forwardNoticeSync with number resolves to For_Account number`() {
        val envelope = envelopeBuilder(myId).build()
        val notice = ForwardNoticeMessage.newBuilder()
            .setConversation(ConversationId.newBuilder().setNumber(otherPeer).build())
            .build()
        val content = Content.newBuilder()
            .setSyncMessage(SyncMessage.newBuilder().setForwardNoticeSync(notice).build())
            .build()

        val data = SignalServiceDataClass(envelope, content, null)
        val conv = data.conversation

        assertTrue(conv is For.Account)
        assertEquals(otherPeer, conv.id)
    }

    
    
    
    @Test
    fun `syncMessage forwardNoticeSync without conversation falls back to self`() {
        val envelope = envelopeBuilder(myId).build()
        val notice = ForwardNoticeMessage.newBuilder().build()
        val content = Content.newBuilder()
            .setSyncMessage(SyncMessage.newBuilder().setForwardNoticeSync(notice).build())
            .build()

        val data = SignalServiceDataClass(envelope, content, null)
        val conv = data.conversation

        assertTrue(conv is For.Account)
        assertEquals(myId, conv.id)
    }

    
    
    
    @Test
    fun `unknown content type still throws IAE from final else branch`() {
        val envelope = envelopeBuilder(peerAccount).build()
        val content = Content.newBuilder().build()

        val data = SignalServiceDataClass(envelope, content, null)
        val ex = assertThrows(IllegalArgumentException::class.java) { data.conversation }
        assertTrue(
            "error message should mention Unknown message type",
            ex.message?.contains("Unknown message type") == true
        )
    }

    
    
    
    @Test
    fun `shouldShowNotification returns false for Content forwardNotice no push`() {
        val envelope = envelopeBuilder(peerAccount).build()
        val content = Content.newBuilder()
            .setForwardNotice(ForwardNoticeMessage.newBuilder().build())
            .build()

        val data = SignalServiceDataClass(envelope, content, null)
        assertFalse(
            "forwardNotice must NOT trigger push notification",
            data.shouldShowNotification
        )
    }

    
    
    
    private fun envelopeBuilder(source: String): Envelope.Builder = Envelope.newBuilder()
        .setSource(source)
        .setSourceDevice(1)
        .setTimestamp(1_700_000_000_000L)
        .setSystemShowTimestamp(1_700_000_000_000L)
        .setMsgType(SignalServiceProtos.Envelope.MsgType.MSG_FORWARD_NOTICE)
}
