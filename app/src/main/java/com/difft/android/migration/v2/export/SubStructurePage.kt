package com.difft.android.migration.v2.export

import com.difft.android.migration.v2.model.AttachmentOwner
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.models.DBForwardModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.MessageModel









internal class SubStructurePage internal constructor(
    private val attachmentsByMessage: Map<String, List<AttachmentModel>>,
    private val forwardsByContext: Map<Long, List<ForwardModel>>,
    private val attachmentsByForward: Map<Long, List<AttachmentModel>>,
) {
    
    fun attachmentsOf(m: MessageModel): List<AttachmentModel> = attachmentsByMessage[m.id].orEmpty()

    
    fun ownedAttachmentsOf(m: MessageModel): List<NeutralProjections.OwnedAttachment> {
        val fcId = m.forwardContextDatabaseId ?: return emptyList()
        return forwardsByContext[fcId].orEmpty().flatMap { fm ->
            attachmentsByForward[fm.databaseId.toLong()].orEmpty().map { am ->
                NeutralProjections.OwnedAttachment(
                    am, AttachmentOwner.FORWARD, fm.author, NeutralProjections.forwardSentAt(fm), hostMessageId = m.id,
                )
            }
        }
    }

    companion object {
        fun load(readSource: MigrationReadSource, page: List<MessageModel>): SubStructurePage {
            val messageIds = page.map { it.id }
            val attachments = if (messageIds.isEmpty()) {
                emptyList()
            } else {
                readSource.attachment.getAllObjects(DBAttachmentModel.messageId.`in`(messageIds))
            }
            
            
            val contextIds = page.mapNotNull { it.forwardContextDatabaseId }.distinct()
            val forwards = if (contextIds.isEmpty()) {
                emptyList()
            } else {
                readSource.forward.getAllObjects(DBForwardModel.forwardContextDatabaseId.`in`(contextIds))
            }
            val forwardIds = forwards.map { it.databaseId.toLong() }
            val forwardAttachments = if (forwardIds.isEmpty()) {
                emptyList()
            } else {
                readSource.attachment.getAllObjects(DBAttachmentModel.forwardModelDatabaseId.`in`(forwardIds))
            }
            return SubStructurePage(
                attachmentsByMessage = attachments.sortedBy { it.databaseId }.groupBy { it.messageId.orEmpty() },
                forwardsByContext = forwards.sortedBy { it.databaseId }.groupBy { it.forwardContextDatabaseId ?: 0L },
                attachmentsByForward = forwardAttachments.sortedBy { it.databaseId }.groupBy { it.forwardModelDatabaseId ?: 0L },
            )
        }
    }
}
