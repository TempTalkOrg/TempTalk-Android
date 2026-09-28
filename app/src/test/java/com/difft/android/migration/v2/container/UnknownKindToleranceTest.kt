package com.difft.android.migration.v2.container

import com.difft.android.migration.v2.InMemoryWcdbWriteTarget
import com.difft.android.migration.v2.importer.NeutralImporter
import com.difft.android.migration.v2.importer.WcdbWriteTarget
import com.difft.android.migration.v2.model.ContentKind
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contract §7.6 unknown-enum tolerance: an unrecognized `contentKind` string (a newer MINOR's new
 * message type) must decode to the explicit [ContentKind.UNKNOWN] — never Gson's silent null (the
 * NPE path) — and the importer must SILENTLY SKIP the whole record with cascade (its attachment
 * records + byte frames), never abort, never write a placeholder row.
 */
class UnknownKindToleranceTest {

    private val codec = RecordJsonCodec()

    private fun neutralMsg(id: String, kind: ContentKind = ContentKind.TEXT) = NeutralMessage(
        stableId = id, conversationRef = "room-1", sender = "u-a",
        direction = MessageDirection.INCOMING, body = "hi", sentAt = 1L, serverAt = 1L,
        receivedAt = null, expireTimer = 0, contentKind = kind, systemType = null,
        confidential = false,
    )

    // ── codec layer ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `unrecognized contentKind string decodes to UNKNOWN, not null`() {
        val line = codec.encodeRecord(NeutralRecord.Message(neutralMsg("m-1")))
            .replace("\"TEXT\"", "\"HOLOGRAM\"") // a future MINOR's new kind
        val decoded = codec.decodeRecord(line) as NeutralRecord.Message
        assertEquals(ContentKind.UNKNOWN, decoded.value.contentKind)
    }

    @Test
    fun `known contentKind still decodes to its value`() {
        val line = codec.encodeRecord(NeutralRecord.Message(neutralMsg("m-1", ContentKind.CALL)))
        val decoded = codec.decodeRecord(line) as NeutralRecord.Message
        assertEquals(ContentKind.CALL, decoded.value.contentKind)
    }

    @Test
    fun `UNKNOWN is decode-only - encoding it fails fast`() {
        assertFailsWith<IllegalArgumentException> {
            codec.encodeRecord(NeutralRecord.Message(neutralMsg("m-1", ContentKind.UNKNOWN)))
        }
    }

    // ── importer layer: silent skip + cascade + per-record fault tolerance ─────────────────

    @Test
    fun `UNKNOWN message is skipped whole and its attachments cascade-skip`() {
        val target = InMemoryWcdbWriteTarget()
        val importer = NeutralImporter(target, messageAttachmentDir = { "/t/$it/" })

        importer.onRecord(NeutralRecord.Message(neutralMsg("m-known")))
        importer.onRecord(NeutralRecord.Message(neutralMsg("m-alien", ContentKind.UNKNOWN)))
        importer.onRecord(
            NeutralRecord.Attachment(
                NeutralAttachment(
                    stableId = "att-alien", messageRef = "m-alien", contentType = "image/jpeg",
                    size = 10, width = null, height = null, durationMs = null, fileName = "a.jpg",
                )
            )
        )
        importer.finish()

        assertEquals(listOf("m-known"), target.messages.map { it.id }, "unknown-kind message skipped, no placeholder")
        assertTrue(target.attachments.none { it.id == "att-alien" }, "attachment of skipped message cascade-skipped")
        assertTrue(importer.isSkippedBytes("att-alien"), "byte frame of cascade-skipped attachment must be discarded")
        assertTrue(!importer.isSkippedBytes("att-other"), "unrelated refs are not discarded")
    }

    @Test
    fun `an attachment with an owner this build does not know is skipped with its bytes`() {
        val target = InMemoryWcdbWriteTarget()
        val importer = NeutralImporter(target, messageAttachmentDir = { "/t/$it/" })
        importer.onRecord(NeutralRecord.Message(neutralMsg("m-known")))

        // Encode a known attachment, then rewrite its owner to a value outside the enum (a malformed
        // or newer container); Gson decodes that to null, which must not become a failed record.
        val known = codec.encodeRecord(
            NeutralRecord.Attachment(
                NeutralAttachment(
                    stableId = "att-quote", messageRef = "m-known", contentType = "image/jpeg",
                    size = 10, width = null, height = null, durationMs = null, fileName = "q.jpg",
                )
            )
        )
        val alien = known.replace("\"owner\":\"MESSAGE\"", "\"owner\":\"QUOTE\"")
        assertTrue(alien != known, "the fixture must carry an explicit owner to rewrite")
        importer.onRecord(codec.decodeRecord(alien)!!)
        importer.finish()

        assertTrue(target.attachments.none { it.id == "att-quote" }, "unknown-owner attachment is not inserted")
        assertTrue(importer.isSkippedBytes("att-quote"), "its byte frame must be discarded")
        assertEquals(listOf("m-known"), target.messages.map { it.id }, "the import itself goes on")
    }

    @Test
    fun `a single record mapping failure does not abort the import`() {
        val target = InMemoryWcdbWriteTarget()
        val importer = NeutralImporter(target, messageAttachmentDir = { throw IllegalStateException("boom") })

        // This attachment record's placement resolution throws inside onRecord → counted + skipped.
        importer.onRecord(
            NeutralRecord.Attachment(
                NeutralAttachment(
                    stableId = "att-bad", messageRef = "m-x", contentType = "image/jpeg",
                    size = 10, width = null, height = null, durationMs = null, fileName = "a.jpg",
                )
            )
        )
        // The stream continues: a later good record still imports.
        importer.onRecord(NeutralRecord.Message(neutralMsg("m-good")))
        importer.finish()

        assertEquals(listOf("m-good"), target.messages.map { it.id }, "import continued past the failing record")
    }

    @Test
    fun `a room-state recompute failure does not fail the import`() {
        val target = mockk<WcdbWriteTarget>(relaxed = true)
        every { target.roomIdsWithMessages() } throws IllegalStateException("staging query failed")
        val importer = NeutralImporter(target, messageAttachmentDir = { "/t/$it/" })

        importer.onRecord(NeutralRecord.Message(neutralMsg("m-1")))
        importer.finish()

        verify { target.roomIdsWithMessages() }
    }

    // ── version gate: newer-MAJOR detection (upgrade-guidance UX, contract §7.7) ───────────

    @Test
    fun `isNewerThanSupported distinguishes newer MAJOR from older or malformed`() {
        assertTrue(FormatCompat.isNewerThanSupported("2.0"))
        assertTrue(!FormatCompat.isNewerThanSupported("1.9"))
        assertTrue(!FormatCompat.isNewerThanSupported("0.9"))
        assertTrue(!FormatCompat.isNewerThanSupported("garbage"))
    }
}
