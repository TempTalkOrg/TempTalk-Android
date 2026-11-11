package org.difft.app.database

import difft.android.messageserialization.model.Attachment
import org.difft.app.database.models.AttachmentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test







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
    fun `a row with no server id and no content type still maps`() {
        
        
        val model = row(localId = null).also {
            it.authorityId = null
            it.contentType = null
        }

        val mapped = model.toAttachment()

        assertEquals(NO_SERVER_ATTACHMENT_ID, mapped.authorityId)
        assertEquals(GENERIC_CONTENT_TYPE, mapped.contentType)
        assertEquals("photo.jpg", mapped.fileName)
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
        
        
        
        
        val fresh = domainAttachment("local-abc").copy(id = "")

        assertEquals(null, fresh.toAttachmentModel("msg-1").id)
        assertEquals(null, fresh.toAttachmentModel(7L).id)
        
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

        
        
        
        assertEquals(first.localId, second.localId)
        assertEquals(nullRow.synthesizedLocalId(), first.localId)
        
        assertEquals(null, nullRow.localId)
        
        
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
