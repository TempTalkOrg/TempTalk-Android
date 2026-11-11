package com.difft.android.websocket.api.messages

import com.difft.android.websocket.api.util.transformGroupIdFromLocalToServer
import com.difft.android.websocket.api.util.transformGroupIdFromServerToLocal
import com.google.protobuf.ByteString
import difft.android.messageserialization.For
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ConversationId
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.DataMessage
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MsgExtra















class ConversationGidNormalizationTest {

    private val gidA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" 
    private val gidB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" 

    
    @Test
    fun `NM1 same group bytes both sides processes`() {
        val bytes = gidA.toByteArray(Charsets.UTF_8)
        val content = For.Group(bytes.transformGroupIdFromServerToLocal())
        val envelope = For.Group(bytes.transformGroupIdFromServerToLocal())
        assertEquals(content, envelope)
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(content, envelope, isSyncOrSelf = false)
        )
    }

    
    @Test
    fun `NM2 different group bytes reject`() {
        val content = For.Group(gidA.toByteArray(Charsets.UTF_8).transformGroupIdFromServerToLocal())
        val envelope = For.Group(gidB.toByteArray(Charsets.UTF_8).transformGroupIdFromServerToLocal())
        assertNotEquals(content, envelope)
        assertEquals(
            ConversationVerdict.REJECT,
            crossCheckConversation(content, envelope, isSyncOrSelf = false)
        )
    }

    
    @Test
    fun `NM3 content path and envelope path reduce identical bytes identically`() {
        val bytes = gidA.toByteArray(Charsets.UTF_8) 

        val content = Content.newBuilder()
            .setDataMessage(
                DataMessage.newBuilder()
                    .setGroup(
                        DataMessage.Group.newBuilder()
                            .setId(ByteString.copyFrom(bytes))
                            .setType(DataMessage.Group.Type.DELIVER)
                            .build()
                    )
                    .build()
            )
            .build()

        val envelope = Envelope.newBuilder()
            .setSource("+15551234")
            .setSourceDevice(1)
            .setTimestamp(1_700_000_000_000L)
            .setMsgExtra(
                MsgExtra.newBuilder()
                    .setConversationId(
                        ConversationId.newBuilder()
                            .setGroupId(ByteString.copyFrom(bytes))
                            .build()
                    )
                    .build()
            )
            .build()

        val data = SignalServiceDataClass(envelope, content, null)
        val contentFor = data.conversation                 
        val envelopeFor = data.envelopeConversation         

        assertTrue(contentFor is For.Group)
        assertTrue(envelopeFor is For.Group)
        assertEquals(contentFor, envelopeFor)
        assertEquals(
            ConversationVerdict.PROCESS,
            crossCheckConversation(contentFor, envelopeFor!!, isSyncOrSelf = false)
        )
    }

    
    @Test
    fun `NM-PLAIN-RT plain ASCII round trip is identity`() {
        val local = gidA 
        val server = local.transformGroupIdFromLocalToServer() 
        val back = server.transformGroupIdFromServerToLocal()  
        assertEquals(local, back)
    }

    
    
    
    
    @Test
    fun `NM-WEEK legacy WEEK round trip is identity`() {
        val local = "WEEK0123456789ABCDEF0123456789ABCDEF" 
        val server = local.transformGroupIdFromLocalToServer() 
        assertEquals("WEEK encode must produce 16 bytes", 16, server.size)
        val back = server.transformGroupIdFromServerToLocal()  
        assertEquals(local, back)
    }
}
