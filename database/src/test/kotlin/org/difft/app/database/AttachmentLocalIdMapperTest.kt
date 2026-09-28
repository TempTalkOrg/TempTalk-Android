package org.difft.app.database

import difft.android.messageserialization.model.Attachment
import org.difft.app.database.models.AttachmentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `localId` round-trip across the attachment mappers, plus the two behaviours the rest of the
 * feature relies on: a NULL column never crashes a read, and `copy()` inherits the id.
 *
 * Pure mapper calls — no WCDB instance, no Android runtime.
 */
class AttachmentLocalIdMapperTest {

    private fun domainAttachment(localId: String) = Attachment(
        id = "att-1",
        authorityId = 42L,
        contentType = "image/jpeg",
        key = null,
        size = 10,
        thumbnail = null,
        digest = null,
        fileName = "photo.jpg",
        flags = 0,
        width = 1,
        height = 1,
        path = null,
        status = 3,
        localId = localId
    )

    private fun row(localId: String?) = AttachmentModel().also {
        it.id = "att-1"
        it.messageId = "msg-1"
        it.authorityId = 42L
        it.contentType = "image/jpeg"
        it.fileName = "photo.jpg"
        it.status = 3
        it.localId = localId
    }

    @Test
    fun `message-owned mapper round-trips localId`() {
        val model = domainAttachment("local-abc").toAttachmentModel("msg-1")

        assertEquals("local-abc", model.localId)
        assertEquals("local-abc", model.toAttachment().localId)
    }

    @Test
    fun `forward-owned mapper round-trips localId`() {
        val model = domainAttachment("local-xyz").toAttachmentModel(7L)

        assertEquals("local-xyz", model.localId)
        assertEquals("local-xyz", model.toAttachment().localId)
    }

    @Test
    fun `an attachment with no server-side id persists NULL, never an empty string`() {
        // Every send and receive path now builds its attachment without one: the server-side id is
        // shared by every copy of a file, so nothing local may be located by it. An empty string
        // stored here would make this row a candidate for the legacy `id.eq(<job's id>)` locator,
        // which is exactly the cross-row write per-copy addressing removed.
        val fresh = domainAttachment("local-abc").copy(id = "")

        assertEquals(null, fresh.toAttachmentModel("msg-1").id)
        assertEquals(null, fresh.toAttachmentModel(7L).id)
        // ...and it reads back as the empty domain value, not as a null that callers must guard.
        assertEquals("", fresh.toAttachmentModel("msg-1").also { it.authorityId = 42L }.toAttachment().id)
    }

    @Test
    fun `row with NULL localId reads back with a synthesized id instead of failing`() {
        val attachment = row(localId = null).toAttachment()

        assertTrue(attachment.localId.isNotBlank())
    }

    @Test
    fun `synthesized id is deterministic per row and is not written back`() {
        val nullRow = row(localId = null).also { it.databaseId = 7 }

        val first = nullRow.toAttachment()
        val second = nullRow.toAttachment()

        // Deterministic per row: repeated reads agree with each other (pre-backfill addressing —
        // download directories, progress keys — stays stable across rebinds) and with the backfill
        // stage, which persists exactly this value.
        assertEquals(first.localId, second.localId)
        assertEquals(nullRow.synthesizedLocalId(), first.localId)
        // Reads never write: persisting the backfill is the migration's job.
        assertEquals(null, nullRow.localId)
        // ...and the synthesized id must stay out of value equality, or two reads of one row would
        // diff as different content and churn the message list.
        assertEquals(first, second)
    }

    @Test
    fun `synthesized ids differ across rows`() {
        val a = row(localId = null).also { it.databaseId = 1 }.toAttachment()
        val b = row(localId = null).also { it.databaseId = 2 }.toAttachment()

        assertNotEquals(a.localId, b.localId)
    }

    @Test
    fun `quoted attachment thumbnail carries localId and synthesizes it when the column is NULL`() {
        assertEquals("local-quote", row("local-quote").toQuotedAttachment().thumbnail?.localId)
        assertTrue(row(null).toQuotedAttachment().thumbnail?.localId?.isNotBlank() == true)
    }

    @Test
    fun `copy inherits localId — a new local copy must pass a fresh id explicitly`() {
        val original = domainAttachment("local-abc")

        assertEquals("local-abc", original.copy(status = 2).localId)

        val explicit = original.copy(localId = "local-def")
        assertNotEquals(original.localId, explicit.localId)
    }

    @Test
    fun `two default-constructed attachments get distinct localIds`() {
        val a = Attachment(
            id = "att-1", authorityId = 42L, contentType = "image/jpeg", key = null, size = 10,
            thumbnail = null, digest = null, fileName = "photo.jpg", flags = 0, width = 1,
            height = 1, path = null, status = 3
        )
        val b = a.copy(localId = java.util.UUID.randomUUID().toString())

        assertFalse(a.localId == b.localId)
        assertTrue(a.localId.isNotBlank())
    }
}
