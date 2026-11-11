package com.difft.android.chat.media

import com.difft.android.base.utils.application
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.util.FileDecryptionUtil
import com.difft.android.websocket.api.crypto.AttachmentCipherStreamUtil
import com.difft.android.selector.basic.PictureSelectionPreviewModel
import com.difft.android.selector.entity.LocalMedia
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.normalizeContentType





object AttachmentPreview {

    fun localMediaFor(attachment: Attachment): LocalMedia {
        val fileName = attachment.fileName ?: ""
        val basePath = AttachmentPathResolver.fileFor(attachment)

        
        
        
        
        
        
        
        
        if (!EncryptedAttachmentAccess.encryptedFile(basePath).exists()) {
            return LocalMedia.generateLocalMedia(application, basePath)
        }

        val uri = EncryptedAttachmentAccess.contentUriFromBasePath(basePath)
        return LocalMedia.create().apply {
            path = uri.toString()
            realPath = basePath
            this.fileName = fileName
            
            mimeType = normalizeContentType(attachment.contentType)
            width = attachment.width
            height = attachment.height
            size = runCatching {
                val encFile = EncryptedAttachmentAccess.encryptedFile(basePath)
                
                
                FileDecryptionUtil.exactPlaintextLength(encFile, attachment.key)
                    .takeIf { it >= 0 }
                    ?: AttachmentCipherStreamUtil.getPlaintextLength(encFile.length())
            }.getOrDefault(0L)
        }
    }
}






fun PictureSelectionPreviewModel.applyExportPolicy(isConfidential: Boolean): PictureSelectionPreviewModel =
    isHidePreviewDownload(isConfidential).isHidePreviewShare(isConfidential)
