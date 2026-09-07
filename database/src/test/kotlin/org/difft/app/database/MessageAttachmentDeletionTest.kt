package org.difft.app.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which directories deleting a NORMAL message removes.
 *
 * Deletion used to name a single directory — the message's own — which was the whole address a
 * message's attachments had. With per-copy addressing every attachment ROW names its own directory,
 * and a message with two attachments has two of them; naming only the message would leave both files
 * on disk with nothing left to drive their removal.
 *
 * The message key is still named while legacy addresses may exist, so a file staged or received
 * before the flip is reclaimed too. That branch retires with the migration.
 */
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
        // Defensive: a row whose localId happens to equal the message id (a pre-migration row that
        // adopted the message key) must not make the same directory be walked twice.
        val keys = messageAttachmentDirectoryKeys(
            messageId = "msg-1",
            rowLocalIds = listOf("msg-1", "local-b", "local-b"),
            legacyWindowOpen = true
        )

        assertEquals(listOf("msg-1", "local-b"), keys)
    }

    @Test
    fun `the legacy window is open by default`() {
        // A device that has not reported a completed migration must still clear legacy addresses.
        assertTrue(LegacyAttachmentAddresses.isWindowOpen)
    }
}
