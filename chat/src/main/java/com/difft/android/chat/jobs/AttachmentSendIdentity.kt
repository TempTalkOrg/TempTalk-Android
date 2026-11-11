package com.difft.android.chat.jobs

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.attachment.AttachmentRowTarget
import com.difft.android.chat.attachment.adoptableLocalId
import com.difft.android.chat.attachment.attachmentRowTarget
import com.tencent.wcdb.base.Value
import difft.android.messageserialization.model.Attachment
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.synthesizedLocalId
import org.difft.app.database.wcdb
import java.io.File















@Suppress("BlockingWcdbInSuspend")
internal class AttachmentSendIdentity(private val messageId: String) {

    




    private var recoveredLocalId: String? = null

    fun localIdOf(attachment: Attachment): String? {
        val localId: String? = attachment.localId
        return localId?.takeIf { it.isNotEmpty() } ?: recoveredLocalId
    }

    











    fun recoverLocalId(attachment: Attachment): String? {
        if (attachment.id.isEmpty()) return null
        val candidates = wcdb.attachment.getAllObjects(
            DBAttachmentModel.id.eq(attachment.id).and(DBAttachmentModel.messageId.eq(messageId))
        )
        val recovered = adoptableLocalId(candidates, { it.localId }, { it.synthesizedLocalId() }) ?: return null
        val row = candidates.single()
        if (row.localId.isNullOrEmpty()) {
            wcdb.attachment.updateValue(
                recovered,
                DBAttachmentModel.localId,
                DBAttachmentModel.databaseId.eq(row.databaseId)
            )
        }
        recoveredLocalId = recovered
        L.i { "[PushTextSendJob] recovered attachment localId for legacy job, messageId=$messageId" }
        return recovered
    }

    






    fun updateRow(attachment: Attachment) {
        wcdb.attachment.updateRow(
            arrayOf(
                Value(attachment.authorityId),
                Value(attachment.key),
                Value(attachment.digest),
                Value(attachment.status)
            ),
            arrayOf(
                DBAttachmentModel.authorityId,
                DBAttachmentModel.key,
                DBAttachmentModel.digest,
                DBAttachmentModel.status
            ),
            rowCondition(attachment)
        )
    }

    




    private fun rowCondition(attachment: Attachment) =
        when (val target = attachmentRowTarget(localIdOf(attachment), attachment.id, messageId)) {
            is AttachmentRowTarget.ByLocalId -> DBAttachmentModel.localId.eq(target.localId)
            is AttachmentRowTarget.ByIdAndMessage ->
                DBAttachmentModel.id.eq(target.attachmentId).and(DBAttachmentModel.messageId.eq(target.messageId))

            is AttachmentRowTarget.ById -> DBAttachmentModel.id.eq(target.attachmentId)
        }

    















    fun resendSourcePath(attachment: Attachment): String? {
        if (attachment.authorityId != 0L) return null
        if (!attachment.fileName.isNullOrEmpty()) {
            val staged = File(AttachmentPathResolver.materializedFileFor(attachment, messageId))
            if (staged.isFile && staged.length() > 0) return staged.path
        }
        throw MissingUploadSourceException(messageId)
    }

    




    fun uploadSourcePath(attachment: Attachment): String? =
        attachment.path?.takeIf { File(it).let { file -> file.isFile && file.length() > 0 } }
            ?: resendSourcePath(attachment)

    





    fun hasUploadSource(attachment: Attachment): Boolean = try {
        uploadSourcePath(attachment)
        true
    } catch (_: MissingUploadSourceException) {
        false
    } catch (e: Exception) {
        L.w { "[ChatResend] source probe failed messageId=$messageId: ${e.stackTraceToString()}" }
        true
    }
}


internal class MissingUploadSourceException(messageId: String) :
    IllegalStateException("attachment never uploaded and staged source missing messageId=$messageId")
