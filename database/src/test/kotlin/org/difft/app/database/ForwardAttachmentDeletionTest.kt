package org.difft.app.database

import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test





class ForwardAttachmentDeletionTest {

    private fun attachment(localId: String, authorityId: Long = 777L) = Attachment(
        id = "server-id",
        authorityId = authorityId,
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
        status = AttachmentStatus.SUCCESS.code,
        localId = localId,
        isForwardCopy = true
    )

    @Test
    fun `every attachment contributes a directory, not just the first`() {
        val keys = forwardAttachmentDirectoryKeys(
            listOf(attachment("local-a"), attachment("local-b"), attachment("local-c"))
        )

        assertEquals(listOf("local-a", "local-b", "local-c"), keys)
    }

    @Test
    fun `the shared authorityId directory is never removed`() {
        
        val keys = forwardAttachmentDirectoryKeys(
            listOf(attachment("local-a", authorityId = 777L), attachment("local-b", authorityId = 777L))
        )

        assertTrue(keys.none { it == "777" })
        assertEquals(setOf("local-a", "local-b"), keys.toSet())
    }

    @Test
    fun `a blank local identity contributes nothing rather than a bogus directory`() {
        val keys = forwardAttachmentDirectoryKeys(listOf(attachment(""), attachment("local-b")))

        assertEquals(listOf("local-b"), keys)
    }

    @Test
    fun `deleting a node with no attachments removes nothing`() {
        assertEquals(emptyList<String>(), forwardAttachmentDirectoryKeys(emptyList()))
    }
}
