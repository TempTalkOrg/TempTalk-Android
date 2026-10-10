package com.difft.android.migration.v2.importer

import android.util.Base64
import com.difft.android.migration.v2.InMemoryWcdbWriteTarget
import com.difft.android.migration.v2.model.ContentKind
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.AttachmentOwner
import com.difft.android.migration.v2.model.NeutralForward
import com.difft.android.migration.v2.model.NeutralForwardContext
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.restore.FaultIsolatingOutputStream
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import org.difft.app.database.GENERIC_CONTENT_TYPE
import org.difft.app.database.NO_SERVER_ATTACHMENT_ID
import org.junit.After
import org.junit.Before
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

    /** `AttachmentStatus.EXPIRED.code` — the terminal state for a pointer nothing can ever fetch. */
    private val statusExpired = 5

    /** The importer decodes keys with the Android Base64, a stub on the plain JVM. */
    @Before
    fun stubBase64() {
        mockkStatic(Base64::class)
        every { Base64.decode(any<String>(), any()) } answers { java.util.Base64.getDecoder().decode(firstArg<String>()) }
    }

    @After
    fun unstubBase64() = unmockkStatic(Base64::class)

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

    /** A source that had bytes but no server id and no type (Desktop, or a never-uploaded row). */
    private fun untypedAttachment(id: String, messageRef: String) = NeutralRecord.Attachment(
        NeutralAttachment(
            stableId = id, messageRef = messageRef, contentType = null, size = 10,
            width = null, height = null, durationMs = null, fileName = "a.bin",
            serverAttachmentId = null,
        )
    )

    private fun importerOver(target: WcdbWriteTarget) =
        NeutralImporter(target, messageAttachmentDir = { "/t/$it/" })

    private fun statusOf(target: InMemoryWcdbWriteTarget, id: String) =
        target.attachments.first { it.id == id }.status

    @Test
    fun `a row with no server id and no content type lands with the shared sentinels`() {
        val target = InMemoryWcdbWriteTarget()
        val importer = importerOver(target)
        importer.onRecord(message("m1"))
        importer.onRecord(untypedAttachment("a1", "m1"))
        importer.finish()

        val row = target.attachments.first { it.id == "a1" }
        assertEquals(NO_SERVER_ATTACHMENT_ID, row.authorityId)
        assertEquals(GENERIC_CONTENT_TYPE, row.contentType)
    }

    @Test
    fun `a pointer with no usable server id or key lands as expired rather than waiting for a download`() {
        val target = InMemoryWcdbWriteTarget()
        val importer = importerOver(target)
        importer.onRecord(message("m1"))
        // Absent id, the 0 sentinel older Android containers wrote, and an empty key all mean "none".
        importer.onRecord(pointer("a-null", serverAttachmentId = null, encryptionKey = "a2V5"))
        importer.onRecord(pointer("a-zero", serverAttachmentId = 0L, encryptionKey = "a2V5"))
        importer.onRecord(pointer("a-nokey", serverAttachmentId = 42L, encryptionKey = ""))
        importer.finish()

        assertEquals(statusExpired, statusOf(target, "a-null"))
        assertEquals(statusExpired, statusOf(target, "a-zero"))
        assertEquals(statusExpired, statusOf(target, "a-nokey"))
    }

    private fun pointer(id: String, serverAttachmentId: Long?, encryptionKey: String) = NeutralRecord.Attachment(
        NeutralAttachment(
            stableId = id, messageRef = "m1", contentType = "image/jpeg", size = 10,
            width = null, height = null, durationMs = null, fileName = "a.jpg",
            pointerOnly = true, serverAttachmentId = serverAttachmentId, encryptionKey = encryptionKey,
        )
    )

    @Test
    fun `a forward-owned attachment whose owner omitted its author still re-links`() {
        val target = InMemoryWcdbWriteTarget()
        val importer = importerOver(target)
        importer.onRecord(
            NeutralRecord.Message(
                message("m1").value.copy(
                    forwardContext = NeutralForwardContext(roots = listOf(NeutralForward(author = "", sentAt = 8, body = null))),
                )
            )
        )
        importer.onRecord(
            NeutralRecord.Attachment(
                NeutralAttachment(
                    stableId = "a1", messageRef = "m1", contentType = "image/jpeg", size = 10,
                    width = null, height = null, durationMs = null, fileName = "a.jpg",
                    owner = AttachmentOwner.FORWARD, ownerAuthor = null, ownerSentAt = 8,
                )
            )
        )
        importer.finish()

        assertEquals(target.forwards.single().databaseId.toLong(), target.attachments.first { it.id == "a1" }.forwardModelDatabaseId)
    }

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
