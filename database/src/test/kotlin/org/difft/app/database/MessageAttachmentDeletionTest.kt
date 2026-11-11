package org.difft.app.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test





class MessageAttachmentDeletionTest {

    @Test
    fun `every attachment row contributes its own directory`() {
        val keys = messageAttachmentDirectoryKeys(
            messageId = "msg-1",
            rowLocalIds = listOf("local-a", "local-b"),
            legacyWindowOpen = false
        )

        assertEquals(listOf("local-a", "local-b"), keys)
    }

    @Test
    fun `the legacy message directory is cleared alongside while the window is open`() {
        val keys = messageAttachmentDirectoryKeys(
            messageId = "msg-1",
            rowLocalIds = listOf("local-a"),
            legacyWindowOpen = true
        )

        assertEquals(listOf("local-a", "msg-1"), keys)
    }

    @Test
    fun `the legacy branch self-extinguishes once the window is closed`() {
        val keys = messageAttachmentDirectoryKeys(
            messageId = "msg-1",
            rowLocalIds = listOf("local-a"),
            legacyWindowOpen = false
        )

        assertTrue(keys.none { it == "msg-1" })
    }

    @Test
    fun `a row that names no local id contributes nothing rather than a bogus directory`() {
        val keys = messageAttachmentDirectoryKeys(
            messageId = "msg-1",
            rowLocalIds = listOf(null, "", "local-b"),
            legacyWindowOpen = false
        )

        assertEquals(listOf("local-b"), keys)
    }

    @Test
    fun `a message with no attachment rows still clears its legacy directory`() {
        assertEquals(
            listOf("msg-1"),
            messageAttachmentDirectoryKeys("msg-1", emptyList(), legacyWindowOpen = true)
        )
        assertEquals(
            emptyList<String>(),
            messageAttachmentDirectoryKeys("msg-1", emptyList(), legacyWindowOpen = false)
        )
    }

    @Test
    fun `a directory is never deleted twice in one pass`() {
        
        
        val keys = messageAttachmentDirectoryKeys(
            messageId = "msg-1",
            rowLocalIds = listOf("msg-1", "local-b", "local-b"),
            legacyWindowOpen = true
        )

        assertEquals(listOf("msg-1", "local-b"), keys)
    }

    @Test
    fun `the legacy window is open by default`() {
        
        assertTrue(LegacyAttachmentAddresses.isWindowOpen)
    }
}
