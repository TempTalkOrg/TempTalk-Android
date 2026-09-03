package com.difft.android.websocket.api.messages

import com.google.protobuf.ByteString
import difft.android.messageserialization.For
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ConversationId
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MsgExtra

/**
 * Unit tests for [extractEnvelopeConversation] (which reuses the private parseToFor).
 * Real protobuf builders, no framework mocks. Covers EX1-5 from design-report §7.2:
 * absent msgExtra, absent conversationId, 1v1 (tag 1), group (tag 2 + normalization),
 * and present-but-empty conversationId. The function is total (never throws).
 *
 * groupId uses a 32-byte UTF-8 id so transformGroupIdFromServerToLocal takes the
 * 32/36 branch (String(UTF8)) and does NOT hit the else branch (no Firebase / L.e).
 */
class EnvelopeConversationExtractionTest {

    private val groupIdString = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 32 bytes ASCII
    private val peerNumber = "+15551234"

    private fun envelope(msgExtra: MsgExtra? = null): Envelope {
        val b = Envelope.newBuilder()
            .setSource(peerNumber)
            .setSourceDevice(1)
            .setTimestamp(1_700_000_000_000L)
        if (msgExtra != null) b.msgExtra = msgExtra
        return b.build()
    }

    // EX1 — no msgExtra at all -> null (absent)
    @Test
    fun `EX1 no msgExtra returns null`() {
        assertNull(extractEnvelopeConversation(envelope()))
    }

    // EX2 — msgExtra set but conversationId unset -> null (absent)
    @Test
    fun `EX2 msgExtra without conversationId returns null`() {
        val env = envelope(MsgExtra.newBuilder().build())
        assertNull(extractEnvelopeConversation(env))
    }

    // EX3 — conversationId.number set -> For.Account (1v1, tag 1)
    @Test
    fun `EX3 conversationId number resolves to For_Account`() {
        val env = envelope(
            MsgExtra.newBuilder()
                .setConversationId(ConversationId.newBuilder().setNumber(peerNumber).build())
                .build()
        )
        val result = extractEnvelopeConversation(env)
        assertTrue(result is For.Account)
        assertEquals(peerNumber, result!!.id)
    }

    // EX4 — conversationId.groupId (32-byte UTF8) -> For.Group(String(UTF8)) (tag 2)
    @Test
    fun `EX4 conversationId groupId resolves to normalized For_Group`() {
        val env = envelope(
            MsgExtra.newBuilder()
                .setConversationId(
                    ConversationId.newBuilder()
                        .setGroupId(ByteString.copyFromUtf8(groupIdString))
                        .build()
                )
                .build()
        )
        val result = extractEnvelopeConversation(env)
        assertTrue(result is For.Group)
        assertEquals(groupIdString, result!!.id)
    }

    // EX5 — conversationId present but empty (neither number nor groupId) -> null (fail-open)
    @Test
    fun `EX5 empty conversationId returns null`() {
        val env = envelope(
            MsgExtra.newBuilder()
                .setConversationId(ConversationId.newBuilder().build())
                .build()
        )
        assertNull(extractEnvelopeConversation(env))
    }
}
