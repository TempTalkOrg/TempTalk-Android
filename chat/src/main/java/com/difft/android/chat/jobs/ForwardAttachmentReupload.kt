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





internal object ForwardAttachmentReupload {

    





















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
        
        
        if (leaf.authorityId != 0L) return
        val key = leaf.key
        if (key == null || key.isEmpty()) return
        
        
        
        
        
        val localId: String? = leaf.localId
        if (localId.isNullOrEmpty()) {
            L.w { "[FwdReupload] leaf has no localId, repair skipped messageId=$messageId" }
            return
        }

        
        
        
        
        
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
            
            throw e
        } catch (e: Exception) {
            
            
            
            L.e { "[FwdReupload] upload failed messageId=$messageId localId=$localId: ${e.stackTraceToString()}" }
            FileUtil.emitProgressUpdate(progressKey, -1)
            throw e
        }

        applyUploadResult(leaf, uploaded)
        identity.updateRow(leaf)
        L.i { "[FwdReupload] re-authorized messageId=$messageId localId=$localId authorityId=${leaf.authorityId} size=${leaf.size}" }
    }

    






    private fun applyUploadResult(leaf: Attachment, uploaded: UploadedAttachment) {
        leaf.authorityId = uploaded.authorizeId
        leaf.key = uploaded.key
        leaf.digest = uploaded.digest
        leaf.fileHash = uploaded.fileHash
        leaf.status = AttachmentStatus.SUCCESS.code
    }

}


internal fun attachmentUploadType(attachment: Attachment): Int = when {
    attachment.isAudioMessage() -> AttachmentUploadType.VOICE
    attachment.size > 200 * 1024 * 1024 -> AttachmentUploadType.LARGE
    else -> AttachmentUploadType.NORMAL
}






internal suspend fun sendRecipients(forWhat: For, groupUtil: GroupUtil): List<String> {
    if (forWhat is For.Account) {
        return listOf(forWhat.id, globalServices.myId)
    }
    val group = groupUtil.getSingleGroupInfo(forWhat.id, false)
    return group?.members?.mapNotNull { it.id }.orEmpty()
}
