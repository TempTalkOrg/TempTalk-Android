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










class EnvelopeConversationExtractionTest {

    private val groupIdString = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" 
    private val peerNumber = "+15551234"

    private fun envelope(msgExtra: MsgExtra? = null): Envelope {
        val b = Envelope.newBuilder()
            .setSource(peerNumber)
            .setSourceDevice(1)
            .setTimestamp(1_700_000_000_000L)
        if (msgExtra != null) b.msgExtra = msgExtra
        return b.build()
    }

    
    @Test
    fun `EX1 no msgExtra returns null`() {
        assertNull(extractEnvelopeConversation(envelope()))
    }

    
    @Test
    fun `EX2 msgExtra without conversationId returns null`() {
        val env = envelope(MsgExtra.newBuilder().build())
        assertNull(extractEnvelopeConversation(env))
    }

    
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
