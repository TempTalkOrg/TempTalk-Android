package com.difft.android.messageserialization.db.store.attachment

import org.difft.app.database.toAttachmentModel
import difft.android.messageserialization.attachment.AttachmentStore
import difft.android.messageserialization.model.Attachment
import io.reactivex.rxjava3.core.Completable
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBAttachmentModel
import javax.inject.Inject

class AttachmentManager
@Inject
constructor(
    private val wcdb: WCDB
) : AttachmentStore {

    override fun putAttachment(messageId: String, vararg attachments: Attachment): Completable = Completable.fromAction {
        val newAttachments = attachments.map { it.toAttachmentModel(messageId) }
        wcdb.attachment.deleteObjects(DBAttachmentModel.id.`in`(newAttachments.map { it.id }).and(DBAttachmentModel.messageId.eq(messageId)))
        wcdb.attachment.insertObjects(newAttachments)
    }
}