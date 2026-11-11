package com.difft.android.chat.media

import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.synthesizedLocalId
import org.difft.app.database.wcdb








fun findAttachmentRowByUriKey(key: String, fileName: String): AttachmentModel? =
    resolveAttachmentRowByUriKey<AttachmentModel>(
        key = key,
        fileName = fileName,
        fileNameOf = { it.fileName },
        synthesizedLocalIdOf = { it.synthesizedLocalId() },
        lookup = { identity, value ->
            when (identity) {
                AttachmentUriIdentity.LOCAL_ID -> wcdb.attachment.getAllObjects(DBAttachmentModel.localId.eq(value))
                AttachmentUriIdentity.MESSAGE_ID -> wcdb.attachment.getAllObjects(DBAttachmentModel.messageId.eq(value))
                AttachmentUriIdentity.AUTHORITY_ID ->
                    value.toLongOrNull()?.let { wcdb.attachment.getAllObjects(DBAttachmentModel.authorityId.eq(it)) }.orEmpty()
                
                
                AttachmentUriIdentity.SYNTHESIZED_LOCAL_ID -> wcdb.attachment.getAllObjects(
                    DBAttachmentModel.localId.isNull().or(DBAttachmentModel.localId.eq(""))
                        .and(DBAttachmentModel.fileName.eq(fileName))
                )
            }
        }
    )
