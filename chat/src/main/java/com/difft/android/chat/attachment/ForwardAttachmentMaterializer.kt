package com.difft.android.chat.attachment

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.attachment.migration.LegacyAttachmentFiles
import com.difft.android.chat.media.EncryptedAttachmentAccess
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import java.io.File
import java.io.IOException














object ForwardAttachmentMaterializer {

    
    data class Result(val copied: Int, val skipped: Int, val failed: Int)

    fun materialize(forwardContext: ForwardContext): Result {
        var copied = 0
        var skipped = 0
        var failed = 0
        forwardContext.forwards?.forEach { forward ->
            forward.walkAttachments { attachment ->
                when (copyOne(attachment)) {
                    CopyOutcome.COPIED -> copied++
                    CopyOutcome.SKIPPED -> skipped++
                    CopyOutcome.FAILED -> failed++
                }
            }
        }
        return Result(copied, skipped, failed)
    }

    private enum class CopyOutcome { COPIED, SKIPPED, FAILED }

    













    internal fun sendSourceBasePath(attachment: Attachment): String? =
        attachment.forwardSourceFilePath ?: legacySourceBasePath(attachment)

    private fun copyOne(attachment: Attachment): CopyOutcome {
        val sourceBasePath = sendSourceBasePath(attachment) ?: return CopyOutcome.SKIPPED
        val fileName = attachment.fileName
        if (fileName.isNullOrEmpty()) return CopyOutcome.SKIPPED
        val targetBasePath = AttachmentPathResolver.fileFor(attachment)
        if (EncryptedAttachmentAccess.isReadable(targetBasePath)) {
            attachment.status = AttachmentStatus.SUCCESS.code
            return CopyOutcome.SKIPPED
        }
        return try {
            var anyCopied = false
            if (EncryptedAttachmentAccess.hasEncrypted(sourceBasePath)) {
                copyFile(
                    EncryptedAttachmentAccess.encryptedFile(sourceBasePath),
                    EncryptedAttachmentAccess.encryptedFile(targetBasePath)
                )
                anyCopied = true
            }
            if (EncryptedAttachmentAccess.hasPlaintext(sourceBasePath)) {
                copyFile(File(sourceBasePath), File(targetBasePath))
                anyCopied = true
            }
            if (!anyCopied) {
                
                CopyOutcome.SKIPPED
            } else {
                attachment.status = AttachmentStatus.SUCCESS.code
                CopyOutcome.COPIED
            }
        } catch (e: Exception) {
            L.w { "[FwdAttachCopy] copy failed localId=${attachment.localId} authorityId=${attachment.authorityId}: ${e.stackTraceToString()}" }
            
            
            CopyOutcome.FAILED
        }
    }

    









    private fun legacySourceBasePath(attachment: Attachment): String? {
        val fallback = attachment.forwardSourceFallback ?: return null
        val path = AttachmentPathResolver.materializedFileFor(fallback.original, fallback.legacyOwnerMessageId)
        return path.takeIf { EncryptedAttachmentAccess.isReadable(it) }
    }

    







    private fun copyFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + LegacyAttachmentFiles.TEMP_SUFFIX)
        try {
            source.copyTo(temp, overwrite = true)
            if (temp.length() != source.length()) {
                throw IOException("[FwdAttachCopy] short copy ${temp.length()}/${source.length()}")
            }
            target.delete()
            if (!temp.renameTo(target)) {
                throw IOException("[FwdAttachCopy] rename to final name failed")
            }
        } finally {
            temp.delete()
            FileUtil.invalidateFileValidity(target.path)
        }
    }

    





    fun discard(forwardContext: ForwardContext) {
        forwardContext.forwards?.forEach { forward ->
            forward.walkAttachments { attachment ->
                attachment.localId.takeIf { it.isNotEmpty() }?.let { FileUtil.deleteMessageFile(it) }
            }
        }
    }

}






internal fun Forward.walkAttachments(action: (Attachment) -> Unit) {
    attachments?.forEach(action)
    forwards?.forEach { it.walkAttachments(action) }
}
