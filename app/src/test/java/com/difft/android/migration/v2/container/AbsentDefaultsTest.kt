package com.difft.android.migration.v2.container

import com.difft.android.migration.v2.model.NeutralRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The container format lets any sender omit any field it has no data for, so every one of these
 * decodes is a shape some peer will legitimately produce. Before [AbsentDefaults] the first
 * dereference of an omitted collection threw NPE and took the whole import down with it.
 */
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
        // Substituting an empty instance here would turn "this message quotes nothing" into a
        // message that quotes an empty message — a wrong row, which is worse than a null.
        val message = decodeMessage(MINIMAL_MESSAGE)

        assertNull("quote", message.quote)
        assertNull("forwardContext", message.forwardContext)
        assertNull("screenShot", message.screenShot)
        // The tri-state anchor: absent must stay absent, since 0 means "explicitly not started".
        assertNull("expireStartedAt", message.expireStartedAt)
    }

    @Test
    fun `an explicit zero anchor survives the round trip as zero, not as absent`() {
        // The tri-state hinges on 0 and "omitted" staying distinguishable on the wire: 0 means the
        // source says the countdown has not started, which the ladder must never override.
        val encoded = codec.encodeRecord(
            (codec.decodeRecord(MINIMAL_MESSAGE) as NeutralRecord.Message).let {
                NeutralRecord.Message(it.value.copy(expireStartedAt = 0L))
            }
        )

        // org.junit.Assert puts the message first.
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
        // The sweep has to recurse: the crash reappears one level down otherwise.
        val message = decodeMessage(
            """{"type":"message","value":{$REQUIRED_MESSAGE_FIELDS,"quote":{"author":"+1","sentAt":7}}}"""
        )

        assertNotNull(message.quote)
        assertTrue("quote.attachmentsMeta", message.quote!!.attachmentsMeta.isEmpty())
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
        // These two distinguish "the sender said nothing" from "the sender said none", so the sweep
        // must leave them alone. The annotation only works if it lands on the FIELD — Kotlin puts it
        // on the constructor parameter by default, where reflection cannot see it and the opt-out is
        // silently inert. This asserts the behaviour, not the declaration.
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
