package com.difft.android.migration.v2.container

import com.difft.android.migration.v2.model.NeutralRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test






class AbsentDefaultsTest {

    private val codec = RecordJsonCodec()

    @Test
    fun `a message carrying only its required fields decodes with empty collections`() {
        val message = decodeMessage(MINIMAL_MESSAGE)

        assertTrue("mentions", message.mentions.isEmpty())
        assertTrue("reactions", message.reactions.isEmpty())
        assertTrue("sharedContacts", message.sharedContacts.isEmpty())
        assertTrue("attachmentRefs", message.attachmentRefs.isEmpty())
        assertTrue("recipients", message.recipients.isEmpty())
        assertTrue("platformExtras", message.platformExtras.isEmpty())
    }

    @Test
    fun `an omitted nested object stays null rather than being invented`() {
        
        
        val message = decodeMessage(MINIMAL_MESSAGE)

        assertNull("quote", message.quote)
        assertNull("forwardContext", message.forwardContext)
        assertNull("screenShot", message.screenShot)
        
        assertNull("expireStartedAt", message.expireStartedAt)
    }

    @Test
    fun `an explicit zero anchor survives the round trip as zero, not as absent`() {
        
        
        val encoded = codec.encodeRecord(
            (codec.decodeRecord(MINIMAL_MESSAGE) as NeutralRecord.Message).let {
                NeutralRecord.Message(it.value.copy(expireStartedAt = 0L))
            }
        )

        
        assertTrue("explicit 0 must be encoded, not dropped: $encoded", encoded.contains("\"expireStartedAt\":0"))
        assertEquals(0L, (codec.decodeRecord(encoded) as NeutralRecord.Message).value.expireStartedAt)
    }

    @Test
    fun `an omitted read ladder decodes as no rows`() {
        val conversation = (codec.decodeRecord(MINIMAL_CONVERSATION) as NeutralRecord.ConversationLocal).value

        assertTrue("readPositions", conversation.readPositions.isEmpty())
        assertTrue("participantReadPositions", conversation.participantReadPositions.isEmpty())
    }

    @Test
    fun `collections nested inside a quote are filled too`() {
        
        val message = decodeMessage(
            """{"type":"message","value":{$REQUIRED_MESSAGE_FIELDS,"quote":{"author":"+1","sentAt":7}}}"""
        )

        assertNotNull(message.quote)
        assertTrue("quote.attachmentsMeta", message.quote!!.attachmentsMeta.isEmpty())
    }

    @Test
    fun `a quote or forward node missing its author decodes with an empty author`() {
        
        
        val message = decodeMessage(
            """{"type":"message","value":{$REQUIRED_MESSAGE_FIELDS,"quote":{"sentAt":7},""" +
                """"forwardContext":{"roots":[{"sentAt":8}]}}}"""
        )

        assertEquals("", message.quote!!.author)
        assertEquals("", message.forwardContext!!.roots.single().author)
    }

    @Test
    fun `present collections survive the sweep untouched`() {
        val message = decodeMessage(
            """{"type":"message","value":{$REQUIRED_MESSAGE_FIELDS,"recipients":["+1","+2"]}}"""
        )

        assertEquals(listOf("+1", "+2"), message.recipients)
    }

    @Test
    fun `an AbsentStaysNull field keeps its null`() {
        
        
        
        
        val identity = codec.decodeRecord(MINIMAL_IDENTITY) as NeutralRecord.Identity

        assertNull("recoverySlotKeks", identity.value.recoverySlotKeks)
        assertNull("recoveryStaleSlots", identity.value.recoveryStaleSlots)
    }

    private fun decodeMessage(line: String) =
        (codec.decodeRecord(line) as NeutralRecord.Message).value

    private companion object {
        const val REQUIRED_MESSAGE_FIELDS =
            """"stableId":"m1","conversationRef":"c1","sender":"+1","sentAt":1,"serverAt":2,""" +
                """"direction":"INCOMING","expireTimer":0,"contentKind":"TEXT","confidential":false"""

        const val MINIMAL_MESSAGE = """{"type":"message","value":{$REQUIRED_MESSAGE_FIELDS}}"""

        const val MINIMAL_CONVERSATION = """{"type":"conversationLocal","value":{"conversationRef":"c1"}}"""

        val MINIMAL_IDENTITY: String = """{"type":"identity","value":{""" +
            """"aciPublicKey":"pub","aciPrivateKey":"priv"}}"""
    }
}
