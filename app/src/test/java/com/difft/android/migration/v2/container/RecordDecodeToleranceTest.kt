package com.difft.android.migration.v2.container

import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Per-record decode faults are local: an optional Long tolerates a bad value, a bad record is skipped. */
class RecordDecodeToleranceTest {

    private val codec = RecordJsonCodec()

    private fun attachment(serverId: Long? = 12345L) = NeutralRecord.Attachment(
        NeutralAttachment(
            stableId = "att-1", messageRef = "m-1", contentType = "image/jpeg", size = 10,
            width = null, height = null, durationMs = null, fileName = "a.jpg", serverAttachmentId = serverId,
        )
    )

    @Test
    fun `serverAttachmentId accepts a number, a decimal string, and reads garbage as absent`() {
        val line = codec.encodeRecord(attachment())
        assertTrue(line.contains("\"serverAttachmentId\":12345"), line)

        val fromNumber = codec.decodeRecord(line) as NeutralRecord.Attachment
        assertEquals(12345L, fromNumber.value.serverAttachmentId)

        val fromString = codec.decodeRecord(line.replace("\"serverAttachmentId\":12345", "\"serverAttachmentId\":\"12345\"")) as NeutralRecord.Attachment
        assertEquals(12345L, fromString.value.serverAttachmentId)

        val fromGarbage = codec.decodeRecord(
            line.replace("\"serverAttachmentId\":12345", "\"serverAttachmentId\":\"forward-9f2a\"")
        ) as NeutralRecord.Attachment
        assertEquals(null, fromGarbage.value.serverAttachmentId, "a non-numeric optional id reads as absent, not as a failed record")
        assertEquals("att-1", fromGarbage.value.stableId, "the rest of the record is intact")
    }

    @Test
    fun `a required field that does not decode skips the record and flags its byte frame`() {
        val line = codec.encodeRecord(attachment()).replace("\"size\":10", "\"size\":\"ten\"")
        val decoded = assertIs<RecordJsonCodec.Decoded.Skipped>(codec.decode(line))
        assertTrue(decoded.undecodable)
        assertTrue(decoded.ownsBytes, "an attachment record owns the byte frame that follows it")
        assertFailsWith<IllegalArgumentException> { codec.decodeRecord(line) }
    }

    @Test
    fun `an unknown type is skipped without owning bytes`() {
        val decoded = assertIs<RecordJsonCodec.Decoded.Skipped>(codec.decode("""{"type":"future","value":{}}"""))
        assertTrue(!decoded.undecodable)
        assertTrue(!decoded.ownsBytes)
    }

    @Test
    fun `a retired nonFriendRemark record is skipped like any unknown type`() {
        // The record type left the contract; a container written before that still parses, with the
        // remark line dropped rather than failing the import.
        val decoded = assertIs<RecordJsonCodec.Decoded.Skipped>(codec.decode("""{"type":"nonFriendRemark","value":{}}"""))
        assertEquals("nonFriendRemark", decoded.tag)
        assertTrue(!decoded.undecodable, "a retired type is unknown, not corrupt")
        assertTrue(!decoded.ownsBytes)
        assertEquals(null, codec.decodeRecord("""{"type":"nonFriendRemark","value":{}}"""))
    }

    @Test
    fun `a line that is not an envelope still throws`() {
        assertFailsWith<Exception> { codec.decode("not json") }
    }
}
