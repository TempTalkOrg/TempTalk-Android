package com.difft.android.chat.jobs

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.attachment.ForwardAttachmentMaterializer
import com.difft.android.chat.attachment.ForwardLeafUpload
import com.difft.android.chat.attachment.LeafBytes
import com.difft.android.chat.attachment.forwardLeafUpload
import com.difft.android.chat.attachment.migration.LegacyAttachmentFiles
import com.difft.android.chat.attachment.walkAttachments
import com.difft.android.chat.fileshare.AttachmentUploadType
import com.difft.android.chat.gif.favorite.AttachmentUploadHelper
import com.difft.android.chat.gif.favorite.UploadedAttachment
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.media.EncryptedAttachmentAccess
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.isAudioMessage
import kotlinx.coroutines.CancellationException
import org.difft.app.database.members
import java.io.File
import java.io.IOException

/**
 * Repairs a forward whose rapid-upload authorization missed, before the message goes on the wire.
 *
 * Forwarding never uploads: it asks the file-sharing service to re-authorize the file the recipients
 * are pointed at. When that misses, the pointer the forward carries is dead — the recipient gets an
 * attachment that can never be downloaded — so this uploads the bytes this device still holds and
 * hands the leaf its new authorization, or fails the send when there are no bytes left to upload.
 * iOS (`OWSUploadOperation`) and Desktop (`makeAttachmentPointer`) both already do this (issue #1181).
 *
 * "No bytes left" is only concluded after the leaf's own address AND the send-time copy source have
 * both come up empty: a leaf whose materialize-time copy missed still has repairable bytes on the
 * device, and failing its send would be a false negative.
 *
 * Runs only for leaves the forward flow marked as missing (`authorityId == 0`), so a hit — every
 * ordinary forward — never reaches any of this.
 *
 * Blocking IO throughout; called from the send job, which runs off the main thread.
 */
internal object ForwardAttachmentReupload {

    /**
     * Uploads every leaf of [forwardContext] whose authorization is missing, writing the new
     * authorization onto the leaf AND into its row.
     *
     * The leaf object is what `createForward` reads to build the wire pointer and what
     * [AttachmentSendIdentity.updateRow] locates the row by, so both sinks are fed from the one
     * mutation here — the wire and the database cannot disagree about key/digest/authorityId.
     * Mutating BEFORE `DataMessageCreator.createFrom` is what makes that true, which is why this must
     * stay ahead of it in `onPushSend`.
     *
     * Every leaf is handled independently, at any nesting depth: a combined forward can carry several
     * attachments and the forward flow authorizes each of them separately.
     *
     * [recipients] is resolved lazily, once, and only when a leaf actually needs a repair: for a
     * group it costs a members lookup (possibly a network fetch), which the ordinary all-authorized
     * forward must not pay — nor fail on.
     *
     * @throws IllegalStateException when a leaf has no local bytes — permanent, so the send fails
     *   without burning retries.
     * @throws java.io.IOException on upload failure, or when [recipients] resolves to nobody —
     *   transient, so the job retries.
     */
    suspend fun repairMissingAuthorizations(
        forwardContext: ForwardContext,
        messageId: String,
        recipients: suspend () -> List<String>,
        uploadHelper: AttachmentUploadHelper,
        identity: AttachmentSendIdentity
    ) {
        val leaves = mutableListOf<Attachment>()
        forwardContext.forwards?.forEach { f -> f.walkAttachments { leaves.add(it) } }
        var resolved: List<String>? = null
        val recipientsOnce: suspend () -> List<String> = {
            resolved ?: recipients().also { resolved = it }
        }
        for (leaf in leaves) {
            repairOne(leaf, messageId, recipientsOnce, uploadHelper, identity)
        }
    }

    private suspend fun repairOne(
        leaf: Attachment,
        messageId: String,
        recipientsOnce: suspend () -> List<String>,
        uploadHelper: AttachmentUploadHelper,
        identity: AttachmentSendIdentity
    ) {
        // Cheap field gates BEFORE any resolver IO: an authorized leaf — every ordinary forward —
        // must not pay the migrating read below, and a keyless leaf was never repairable.
        if (leaf.authorityId != 0L) return
        val key = leaf.key
        if (key == null || key.isEmpty()) return
        // Read as nullable although the property is declared non-null: gson bypasses constructor
        // defaults, so a leaf persisted before the localId column deserializes with a null field
        // (same defense as AttachmentSendIdentity.localIdOf). Such a leaf cannot be addressed
        // per-copy; it also predates the miss marker, so authorityId == 0 is legacy "never
        // uploaded" — keep today's send-as-is behavior rather than crash.
        val localId: String? = leaf.localId
        if (localId.isNullOrEmpty()) {
            L.w { "[FwdReupload] leaf has no localId, repair skipped messageId=$messageId" }
            return
        }

        // The migrating read: a copy staged before per-copy addressing still sits under the owner
        // message's directory — which for a forward being sent IS this message id, exactly as
        // `AttachmentSendIdentity.resendSourcePath` relies on for a message's own attachment. The
        // migration is the only component allowed to know that; probing the address by hand here
        // would miss exactly those files.
        val basePath = AttachmentPathResolver.materializedFileFor(leaf, messageId)
        val action = forwardLeafUpload(
            authorityId = leaf.authorityId,
            key = key,
            fileName = leaf.fileName,
            basePath = basePath,
            bytesAt = { candidate ->
                LeafBytes(
                    hasPlaintext = EncryptedAttachmentAccess.hasPlaintext(candidate),
                    hasCiphertext = EncryptedAttachmentAccess.hasEncrypted(candidate)
                )
            },
            // Second chance before the send is failed: the copy this leaf was supposed to receive can
            // have missed (a full disk, or a source that finished downloading only afterwards) while
            // the bytes are still on the device. Uploaded from the source itself — never copied to
            // this leaf's address, so a confidential source stays without a persistent copy.
            sendSourceBasePath = {
                ForwardAttachmentMaterializer.sendSourceBasePath(leaf)?.also {
                    L.i { "[FwdReupload] leaf address empty, retrying from send source messageId=$messageId localId=$localId" }
                }
            }
        )
        if (action is ForwardLeafUpload.NotNeeded) return

        val progressKey = localId

        if (action is ForwardLeafUpload.NoLocalBytes) {
            L.w { "[FwdReupload] authorization missing and no local bytes messageId=$messageId localId=$localId" }
            leaf.status = AttachmentStatus.FAILED.code
            identity.updateRow(leaf)
            FileUtil.emitProgressUpdate(progressKey, -1)
            throw IllegalStateException("forward attachment authorization missing and no local bytes messageId=$messageId")
        }

        val recipients = recipientsOnce()
        if (recipients.isEmpty()) {
            // Group members unresolved (fetch failed or already in flight): authorizing for nobody
            // would ship an undownloadable pointer as a "successful" repair. Transient, so retry.
            throw IOException("forward repair could not resolve recipients messageId=$messageId")
        }

        val uploadType = attachmentUploadType(leaf)
        val uploaded = try {
            when (action) {
                is ForwardLeafUpload.FromStoredCiphertext -> uploadHelper.uploadStoredCiphertext(
                    ciphertextFile = EncryptedAttachmentAccess.encryptedFile(action.basePath),
                    key = key,
                    plainSize = leaf.size,
                    recipients = recipients,
                    attachmentType = uploadType
                )

                is ForwardLeafUpload.FromPlaintext -> {
                    // Encrypted at rest: at THIS leaf's own address the ciphertext IS the stored form
                    // of the file it was made from, so it is kept there rather than deleted.
                    //
                    // At the send source it is not: that address belongs to the ORIGINAL row, whose
                    // storage this repair does not own. Persisting a ".encrypt" there would (a) make
                    // a truncated legacy plaintext readable — the size check in
                    // EncryptedAttachmentAccess.isReadable(path, size) is short-circuited by any
                    // structurally complete ciphertext, so that bubble would render truncated bytes
                    // forever instead of re-downloading — and (b) publish a non-atomically written
                    // file at an address live bubbles read. So it goes to the migration's own temp
                    // shape (reclaimed by the startup stray-temp sweep after a process death) and is
                    // deleted when the upload finishes.
                    val ownAddress = action.basePath == basePath
                    uploadHelper.encryptAndUpload(
                        file = File(action.basePath),
                        recipients = recipients,
                        attachmentType = uploadType,
                        encryptPath = if (ownAddress) {
                            "${action.basePath}.encrypt"
                        } else {
                            "${action.basePath}.encrypt${LegacyAttachmentFiles.TEMP_SUFFIX}"
                        },
                        deleteEncryptFile = !ownAddress
                    )
                }
            }
        } catch (e: CancellationException) {
            // Not a transfer failure: the job was cancelled. No error log, no failed-progress emit.
            throw e
        } catch (e: Exception) {
            // The bytes are still on disk, so the row's status stays truthful — only the transfer
            // failed. The message itself is marked failed by the job's own error path, and a resend
            // re-enters here because the authorization is still missing.
            L.e { "[FwdReupload] upload failed messageId=$messageId localId=$localId: ${e.stackTraceToString()}" }
            FileUtil.emitProgressUpdate(progressKey, -1)
            throw e
        }

        applyUploadResult(leaf, uploaded)
        identity.updateRow(leaf)
        L.i { "[FwdReupload] re-authorized messageId=$messageId localId=$localId authorityId=${leaf.authorityId} size=${leaf.size}" }
    }

    /**
     * Adopts the upload's identity wholesale. `key` is content-derived so it comes back equal to what
     * the leaf already held, but `digest` does NOT: it is the hash of the ciphertext the server now
     * stores, which differs whenever the bytes were re-encrypted or the server had held a different
     * ciphertext for the same file. Writing every field — rather than only `authorityId` — is what
     * keeps the pointer internally consistent.
     */
    private fun applyUploadResult(leaf: Attachment, uploaded: UploadedAttachment) {
        leaf.authorityId = uploaded.authorizeId
        leaf.key = uploaded.key
        leaf.digest = uploaded.digest
        leaf.fileHash = uploaded.fileHash
        leaf.status = AttachmentStatus.SUCCESS.code
    }

}

/** Upload class of [attachment], shared by the send job's own upload and the forward repair above. */
internal fun attachmentUploadType(attachment: Attachment): Int = when {
    attachment.isAudioMessage() -> AttachmentUploadType.VOICE
    attachment.size > 200 * 1024 * 1024 -> AttachmentUploadType.LARGE
    else -> AttachmentUploadType.NORMAL
}

/**
 * Who an upload must be authorized for: every group member, or the peer plus this account. Shared by
 * the send job's own upload and the forward repair above so the two can never authorize for
 * different audiences.
 */
internal suspend fun sendRecipients(forWhat: For, groupUtil: GroupUtil): List<String> {
    if (forWhat is For.Account) {
        return listOf(forWhat.id, globalServices.myId)
    }
    val group = groupUtil.getSingleGroupInfo(forWhat.id, false)
    return group?.members?.mapNotNull { it.id }.orEmpty()
}
