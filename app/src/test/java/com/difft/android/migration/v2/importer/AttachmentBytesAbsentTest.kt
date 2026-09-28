package com.difft.android.migration.v2.importer

import com.difft.android.migration.v2.InMemoryWcdbWriteTarget
import com.difft.android.migration.v2.model.ContentKind
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.restore.FaultIsolatingOutputStream
import io.mockk.spyk
import io.mockk.verify
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An attachment row is inserted with the "bytes present" status before its byte frame arrives. When
 * the frame carries nothing — empty, unopenable, or failing mid-write — the row must be demoted to
 * the pointer-only status, so the app re-downloads instead of opening a file that is not there.
 *
 * The demotion is applied after the inserts flush: the row is still buffered in its batch when the
 * frame fails, so an immediate update would find no row to correct.
 */
class AttachmentBytesAbsentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val room = "room-1"

    /** `AttachmentStatus.SUCCESS.code` — what a row claims until its bytes are known to be missing. */
    private val statusSuccess = 3

    private fun message(id: String, kind: ContentKind = ContentKind.TEXT) = NeutralRecord.Message(
        NeutralMessage(
            stableId = id, conversationRef = room, sender = "u-alice",
            direction = MessageDirection.INCOMING, body = "hi", sentAt = 1000, serverAt = 1010,
            receivedAt = null, expireTimer = 0, contentKind = kind, systemType = null,
            confidential = false,
        )
    )

    private fun attachment(id: String, messageRef: String) = NeutralRecord.Attachment(
        NeutralAttachment(
            stableId = id, messageRef = messageRef, contentType = "image/jpeg", size = 10,
            width = null, height = null, durationMs = null, fileName = "a.jpg",
        )
    )

    private fun importerOver(target: WcdbWriteTarget) =
        NeutralImporter(target, messageAttachmentDir = { "/t/$it/" })

    private fun statusOf(target: InMemoryWcdbWriteTarget, id: String) =
        target.attachments.first { it.id == id }.status

    @Test
    fun `an attachment whose frame carried nothing is demoted to no bytes`() {
        val target = InMemoryWcdbWriteTarget()
        val importer = importerOver(target)

        importer.onRecord(message("m-1"))
        importer.onRecord(attachment("att-empty", "m-1"))
        importer.onRecord(attachment("att-ok", "m-1"))
        // The frame is reported while both inserts are still buffered in the batch.
        importer.onAttachmentBytesAbsent("att-empty")
        importer.finish()

        assertEquals(0, statusOf(target, "att-empty"), "a row with no bytes must not claim it has them")
        assertEquals(statusSuccess, statusOf(target, "att-ok"), "a row whose bytes landed is untouched")
    }

    @Test
    fun `a write that fails mid frame demotes the row and leaves no partial file`() {
        val target = InMemoryWcdbWriteTarget()
        val importer = importerOver(target)
        importer.onRecord(message("m-1"))
        importer.onRecord(attachment("att-broken", "m-1"))

        // The real seam the orchestrator hands to the reader: the first write failure deletes the
        // partial file and fires the failure hook once.
        val partial = tmp.newFile("att-broken.part")
        val out = FaultIsolatingOutputStream(partial, ThrowingOutputStream()) {
            importer.onAttachmentBytesAbsent("att-broken")
        }
        out.write(ByteArray(4))
        out.close()
        importer.finish()

        assertTrue(out.failed, "the stream recorded the write failure")
        assertFalse(partial.exists(), "the partial file is dropped")
        assertEquals(0, statusOf(target, "att-broken"))
    }

    @Test
    fun `a skipped record's ref is a no-op`() {
        val target = spyk(InMemoryWcdbWriteTarget())
        val importer = importerOver(target)

        // An unknown-kind message cascades: its attachment record is skipped and its frame discarded,
        // so there is no row to correct and no write to make.
        importer.onRecord(message("m-alien", ContentKind.UNKNOWN))
        importer.onRecord(attachment("att-alien", "m-alien"))
        importer.onRecord(message("m-1"))
        importer.onRecord(attachment("att-ok", "m-1"))
        importer.onAttachmentBytesAbsent("att-alien")
        importer.finish()

        assertTrue(importer.isSkippedBytes("att-alien"))
        assertTrue(target.attachments.none { it.id == "att-alien" }, "the skipped record never became a row")
        verify(exactly = 0) { target.markAttachmentBytesAbsent(any()) }
        assertEquals(statusSuccess, statusOf(target, "att-ok"), "an unrelated row is untouched")
    }

    /** Fails every write, the way a full or revoked staging volume does. */
    private class ThrowingOutputStream : OutputStream() {
        override fun write(b: Int): Unit = throw IOException("disk gone")
        override fun write(b: ByteArray, off: Int, len: Int): Unit = throw IOException("disk gone")
    }
}
