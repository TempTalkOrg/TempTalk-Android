package com.difft.android.chat.gif.favorite

import com.difft.android.base.utils.globalServices
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton



@Singleton
class FavoriteAssetUploader @Inject constructor(
    private val uploadHelper: AttachmentUploadHelper
) {
    










    suspend fun transStore(gifFile: File, width: Int, height: Int): FavoriteAttachmentPointer {
        val myId = globalServices.myId
        val uploaded = uploadHelper.encryptAndUpload(gifFile, recipients = listOf(myId))
        return FavoriteAttachmentPointer(
            id = uploaded.attachmentId,
            authorizeId = uploaded.authorizeId,
            key = uploaded.key,
            digest = uploaded.digest,
            fileHash = uploaded.fileHash,
            contentType = "image/webp",
            width = width,
            height = height,
            size = gifFile.length().toInt() 
        )
    }

    









    suspend fun transStoreKnown(
        ref: PendingSource.Message,
        width: Int,
        height: Int,
        size: Int,
        contentType: String,
        downloadPlaintext: suspend () -> File
    ): FavoriteAttachmentPointer {
        
        
        val fast = uploadHelper.existingByFileHash(ref.accountFileHash, listOf(globalServices.myId))
        if (fast != null) {
            return FavoriteAttachmentPointer(
                id = fast.attachmentId,
                authorizeId = fast.authorizeId,
                key = ref.key,                       
                digest = fast.digest,                
                fileHash = ref.accountFileHash,
                contentType = contentType,
                width = width,
                height = height,
                size = size
            )
        }
        
        
        
        val plaintext = downloadPlaintext()
        return try {
            transStore(plaintext, width, height)
        } finally {
            plaintext.delete()
        }
    }
}
