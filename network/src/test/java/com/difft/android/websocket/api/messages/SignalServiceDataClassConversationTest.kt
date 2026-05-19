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

/**
 * Unit tests for two branches in [SignalServiceDataClass.conversation] lazy:
 *   1. `hasForwardNotice()` — primary path (from peer/group/self-as-NTS)
 *   2. `syncMessage.hasForwardNoticeSync()` — 1v1-to-other self-sync path
 *
 * ForwardNoticeMessage.conversation (tag 4) is self-contained. The receiver does
 * NOT depend on server-populated `envelope.msgExtra.conversationId` (custom-notify-specific).
 *
 * Resolution:
 *   Primary:
 *     - payload.conversation.groupId → For.Group
 *     - else                          → For.Account(senderId) — sender IS the peer for 1v1
 *   Self-sync (senderId == myId):
 *     - payload.conversation.groupId → For.Group (defensive; group source doesn't use sync)
 *     - payload.conversation.number  → For.Account(number) — peer uid directly
 *     - else                          → For.Account(senderId=myId) — NTS fallback
 */
class SignalServiceDataClassConversationTest {

    private val myId = "+10000000"
    private val peerAccount = "+15551234"
    private val otherPeer = "+15559999"
    private val groupIdString = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 32 bytes ASCII

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

    // ---------------------------------------------------------------------
    // Rule #1: group scene — payload.conversation.groupId present
    // ---------------------------------------------------------------------
    @Test
    fun `forwardNotice payload groupId resolves to For_Group regardless of envelope_source`() {
        // Primary receive path (sender is someone else).
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

    // ---------------------------------------------------------------------
    // Primary 1v1 — envelope.source IS the peer; payload.number (if any) is ignored.
    // ---------------------------------------------------------------------
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

    // ---------------------------------------------------------------------
    // Self-sync via SyncMessage.forwardNoticeSync — payload.number = peer uid.
    // ---------------------------------------------------------------------
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

    // ---------------------------------------------------------------------
    // Self-sync edge — payload.conversation missing → For.Account(myId) fallback (NTS).
    // ---------------------------------------------------------------------
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

    // ---------------------------------------------------------------------
    // Regression guard — unknown content still throws from the final else branch.
    // ---------------------------------------------------------------------
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

    // ---------------------------------------------------------------------
    // Pin the "forwardNotice 不触发推送" contract — shouldShowNotification returns false.
    // ---------------------------------------------------------------------
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

    // ---------------------------------------------------------------------
    // Helper — build a minimal envelope the SignalServiceDataClass lazies can use.
    // ---------------------------------------------------------------------
    private fun envelopeBuilder(source: String): Envelope.Builder = Envelope.newBuilder()
        .setSource(source)
        .setSourceDevice(1)
        .setTimestamp(1_700_000_000_000L)
        .setSystemShowTimestamp(1_700_000_000_000L)
        .setMsgType(SignalServiceProtos.Envelope.MsgType.MSG_FORWARD_NOTICE)
}
