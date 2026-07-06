package com.difft.android.chat.gif.favorite

import com.difft.android.base.utils.globalServices
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trans-stores a gif as an account-level encrypted attachment (recipients = [myId]) via the
 * shared [AttachmentUploadHelper], returning a [FavoriteAttachmentPointer] for the favorites
 * blob. A fast-pass (isExist) hit makes this near-instant for an already-uploaded gif. See §B5.
 */
@Singleton
class FavoriteAssetUploader @Inject constructor(
    private val uploadHelper: AttachmentUploadHelper
) {
    /**
     * Encrypt + ensure-stored [gifFile] at account level and return its favorites pointer.
     * [width]/[height] are carried into the pointer (the favorites grid needs aspect ratio).
     *
     * Even a favorite sourced from an existing message attachment goes through this (not a raw reuse
     * of the message pointer) for two reasons: (1) it re-authorizes the asset under MY account
     * (recipients = [myId]) so the favorite outlives the message — a message-scoped authorizeId can be
     * GC'd when the message is deleted / expires; (2) if the server copy is already gone, the isExist
     * check misses and the local bytes are re-uploaded, so favoriting still succeeds. When the server
     * still has it, isExist fast-passes (no byte re-upload) and this is near-instant.
     */
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
            size = gifFile.length().toInt() // plaintext byte size, for peer download validation
        )
    }
}
