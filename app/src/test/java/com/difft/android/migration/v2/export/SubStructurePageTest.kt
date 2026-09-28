package com.difft.android.migration.v2.export

import com.difft.android.migration.v2.model.AttachmentOwner
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.MessageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prefetched page answers per message exactly what the per-message queries answered: the
 * message's own rows in row order, and its forward-owned rows tagged with the host message and the
 * forward node's own sentAt.
 */
class SubStructurePageTest {

    private fun att(dbId: Int, messageId: String? = null, forwardPk: Long? = null) = AttachmentModel().apply {
        databaseId = dbId
        this.messageId = messageId
        forwardModelDatabaseId = forwardPk
        id = "att-$dbId"
    }

    private fun fwd(dbId: Int, contextId: Long, author: String, sentAt: Long) = ForwardModel().apply {
        databaseId = dbId
        forwardContextDatabaseId = contextId
        this.author = author
        id = sentAt
    }

    private fun msg(id: String, contextId: Long? = null) = MessageModel().apply {
        this.id = id
        forwardContextDatabaseId = contextId
    }

    @Test
    fun `answers a message's own attachments in row order and nothing of its neighbours`() {
        val page = SubStructurePage(
            attachmentsByMessage = mapOf("m-1" to listOf(att(3, "m-1"), att(7, "m-1")), "m-2" to listOf(att(5, "m-2"))),
            forwardsByContext = emptyMap(),
            attachmentsByForward = emptyMap(),
        )
        assertEquals(listOf("att-3", "att-7"), page.attachmentsOf(msg("m-1")).map { it.id })
        assertEquals(listOf("att-5"), page.attachmentsOf(msg("m-2")).map { it.id })
        assertTrue(page.attachmentsOf(msg("m-3")).isEmpty())
        assertTrue(page.ownedAttachmentsOf(msg("m-1")).isEmpty())
    }

    @Test
    fun `forward-owned attachments carry the host message and the node's own sentAt`() {
        val page = SubStructurePage(
            attachmentsByMessage = emptyMap(),
            forwardsByContext = mapOf(40L to listOf(fwd(11, 40L, "u-a", 1_000L), fwd(12, 40L, "u-b", 2_000L))),
            attachmentsByForward = mapOf(11L to listOf(att(90, forwardPk = 11L)), 12L to listOf(att(91, forwardPk = 12L), att(92, forwardPk = 12L))),
        )
        val owned = page.ownedAttachmentsOf(msg("m-fwd", contextId = 40L))
        assertEquals(listOf("att-90", "att-91", "att-92"), owned.map { it.model.id })
        assertTrue(owned.all { it.owner == AttachmentOwner.FORWARD && it.hostMessageId == "m-fwd" })
        assertEquals(listOf("u-a" to 1_000L, "u-b" to 2_000L, "u-b" to 2_000L), owned.map { it.ownerAuthor to it.ownerSentAt })
        // A message without a forward context, or with one the page did not see, owns nothing.
        assertTrue(page.ownedAttachmentsOf(msg("m-plain")).isEmpty())
        assertTrue(page.ownedAttachmentsOf(msg("m-other", contextId = 41L)).isEmpty())
    }
}
