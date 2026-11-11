package com.difft.android.chat.gif.favorite

import android.util.Base64
import com.difft.android.base.utils.globalServices






data class FavoriteAttachmentPointer(
    val id: String,
    val authorizeId: Long,
    
    val key: ByteArray,
    
    val digest: ByteArray,
    
    val fileHash: String,
    val contentType: String = "image/webp",
    val width: Int,
    val height: Int,
    
    val size: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as FavoriteAttachmentPointer
        return fileHash == other.fileHash
    }

    override fun hashCode(): Int = fileHash.hashCode()
}


data class FavoriteRecord(
    val attachment: FavoriteAttachmentPointer,
    
    val addedListVersion: Long
)


data class FavoriteListPlain(val records: List<FavoriteRecord>)







sealed interface PendingSource {
    
    data object None : PendingSource

    
    data class Remote(val previewUrl: String) : PendingSource

    















    data class Message(
        val messageId: String,
        val fileName: String,
        val attachmentId: String,
        val authorizeId: Long,
        val key: ByteArray,
        val digest: ByteArray,
        val accountFileHash: String
    ) : PendingSource {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            other as Message
            return accountFileHash == other.accountFileHash
        }

        override fun hashCode(): Int = accountFileHash.hashCode()
    }
}







private data class PendingSourceDto(
    val type: Int,
    val previewUrl: String? = null,       
    val messageId: String? = null,        
    val fileName: String? = null,
    val attachmentId: String? = null,
    val authorizeId: Long = 0L,
    val encKeyB64: String? = null,
    val digestB64: String? = null,
    val accountFileHash: String? = null
) {
    companion object {
        const val TYPE_REMOTE = 1
        const val TYPE_MESSAGE = 2
    }
}


fun PendingSource.toJson(): String? = when (this) {
    PendingSource.None -> null
    is PendingSource.Remote -> globalServices.gson.toJson(
        PendingSourceDto(type = PendingSourceDto.TYPE_REMOTE, previewUrl = previewUrl)
    )
    is PendingSource.Message -> globalServices.gson.toJson(
        PendingSourceDto(
            type = PendingSourceDto.TYPE_MESSAGE,
            messageId = messageId,
            fileName = fileName,
            attachmentId = attachmentId,
            authorizeId = authorizeId,
            encKeyB64 = Base64.encodeToString(key, Base64.NO_WRAP),
            digestB64 = Base64.encodeToString(digest, Base64.NO_WRAP),
            accountFileHash = accountFileHash
        )
    )
}


fun pendingSourceFromJson(json: String?): PendingSource? {
    if (json.isNullOrBlank()) return null
    val dto = runCatching { globalServices.gson.fromJson(json, PendingSourceDto::class.java) }.getOrNull() ?: return null
    return when (dto.type) {
        PendingSourceDto.TYPE_REMOTE -> dto.previewUrl?.let { PendingSource.Remote(it) }
        PendingSourceDto.TYPE_MESSAGE -> PendingSource.Message(
            messageId = dto.messageId.orEmpty(),
            fileName = dto.fileName.orEmpty(),
            attachmentId = dto.attachmentId.orEmpty(),
            authorizeId = dto.authorizeId,
            key = dto.encKeyB64?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(0),
            digest = dto.digestB64?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(0),
            accountFileHash = dto.accountFileHash.orEmpty()
        )
        else -> null
    }
}
