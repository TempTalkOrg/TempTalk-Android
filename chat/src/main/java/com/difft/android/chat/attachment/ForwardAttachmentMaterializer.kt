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

/**
 * Gives a freshly minted forward copy its own local file, so a forwarded attachment the user already
 * has is displayed immediately instead of being downloaded again.
 *
 * The bytes are copied as they are stored: the ciphertext is copied as ciphertext (same key, same
 * digest — nothing about the wire payload or the fast-path authorization changes), and a legacy
 * plaintext file is copied alongside it when that is all there is.
 *
 * Best effort by construction. Any failure leaves the copy LOADING, which is exactly the pre-existing
 * download path — the forward send itself must never fail because a local optimization did.
 *
 * Blocking IO: call from an IO dispatcher.
 */
object ForwardAttachmentMaterializer {

    /** Result counters for one forward context, for a single field-diagnostics log line. */
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

    /**
     * The on-disk base path a forward copy's bytes may be taken from at send time: the source
     * captured while the ORIGINAL message context was still in hand, else the fallback resolved
     * through the migration seam.
     *
     * Null whenever no hint is present, which is the ordinary case rather than an error — a
     * confidential source is refused one by construction (`toForwardCopy`), and both hints are
     * transient, so a send job that was serialized and resumed carries neither. Every null degrades
     * to the pre-existing behaviour of whichever caller asked.
     *
     * Shared with the send-time forward repair, which uploads STRAIGHT from this path instead of
     * copying it anywhere: one rule for where a copy's bytes can come from, so the repair can never
     * reach a source the materializer would have refused.
     */
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
                // Readable at capture time, gone by send time (cleanup / deletion raced us).
                CopyOutcome.SKIPPED
            } else {
                attachment.status = AttachmentStatus.SUCCESS.code
                CopyOutcome.COPIED
            }
        } catch (e: Exception) {
            L.w { "[FwdAttachCopy] copy failed localId=${attachment.localId} authorityId=${attachment.authorityId}: ${e.stackTraceToString()}" }
            // A failed copy leaves nothing under the real name (temp + rename below), so the copy
            // stays LOADING and simply downloads.
            CopyOutcome.FAILED
        }
    }

    /**
     * Second chance for a copy whose capture found nothing: the original's file may still be at a
     * pre-per-copy address, which capture (on the main thread) is not allowed to go looking for.
     *
     * The ONE place the fallback hint is read, and it is read through the migration seam: this is
     * the IO context the seam requires, [AttachmentPathResolver.materializedFileFor] brings the file
     * to the original's OWN address, and the original — not this copy — is what gets resolved,
     * because the copy's identity was minted after the file was written. A miss simply answers null
     * and the copy degrades to LOADING exactly as before.
     */
    private fun legacySourceBasePath(attachment: Attachment): String? {
        val fallback = attachment.forwardSourceFallback ?: return null
        val path = AttachmentPathResolver.materializedFileFor(fallback.original, fallback.legacyOwnerMessageId)
        return path.takeIf { EncryptedAttachmentAccess.isReadable(it) }
    }

    /**
     * Temp + length check + atomic rename — same discipline as the migration's copy primitive. A
     * mid-copy failure (disk full) or process death must never leave a partial file under the real
     * name: the plaintext shape has no structural check that would reject it later, and the forward
     * flow marks the copy SUCCESS, so a partial would render broken forever without re-downloading.
     * The temp shares the migration's suffix, so a process-death leftover is reclaimed by its
     * startup stray-temp sweep.
     */
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

    /**
     * Deletes the per-copy directories [materialize] created for [forwardContext]. For a forward
     * send that aborts between materialization and message insert: the freshly minted copies belong
     * to no row, so row-driven deletion and the migration's row-driven sweep could never reclaim
     * them. Safe by construction — the localIds are minted per target, no other message shares them.
     */
    fun discard(forwardContext: ForwardContext) {
        forwardContext.forwards?.forEach { forward ->
            forward.walkAttachments { attachment ->
                attachment.localId.takeIf { it.isNotEmpty() }?.let { FileUtil.deleteMessageFile(it) }
            }
        }
    }

}

/**
 * Applies [action] to every attachment of this forward and of every forward nested under it.
 * Shared by the materializer's copy and discard passes and by the send-time forward repair, so
 * every tree walker traverses nesting with one rule.
 */
internal fun Forward.walkAttachments(action: (Attachment) -> Unit) {
    attachments?.forEach(action)
    forwards?.forEach { it.walkAttachments(action) }
}
