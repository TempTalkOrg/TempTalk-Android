package com.difft.android.chat.attachment

import util.FileSystemUtils





class UploadIdentity(
    val attachmentId: String,
    
    val digest: ByteArray,
    
    val adoptedFromServer: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as UploadIdentity
        return attachmentId == other.attachmentId &&
            digest.contentEquals(other.digest) &&
            adoptedFromServer == other.adoptedFromServer
    }

    override fun hashCode(): Int =
        (attachmentId.hashCode() * 31 + digest.contentHashCode()) * 31 + adoptedFromServer.hashCode()
}










internal fun decodeCipherHashOrNull(cipherHash: String?): ByteArray? {
    val hex = cipherHash?.takeIf { it.isNotBlank() } ?: return null
    if (!hex.all { it.digitToIntOrNull(16) != null }) return null
    return runCatching { FileSystemUtils.decodeDigestHex(hex) }.getOrNull()?.takeIf { it.isNotEmpty() }
}

























fun resolveUploadIdentity(
    localAttachmentId: String,
    localDigest: ByteArray,
    respAttachmentId: String?,
    respCipherHash: String?
): UploadIdentity {
    val serverId = respAttachmentId?.takeIf { it.isNotBlank() }
    
    
    
    val serverDigest = decodeCipherHashOrNull(respCipherHash)
        ?.takeIf { localDigest.isEmpty() || it.size == localDigest.size }
        ?: return UploadIdentity(localAttachmentId, localDigest, adoptedFromServer = false)
    val attachmentId = serverId ?: localAttachmentId
    val differs = attachmentId != localAttachmentId || !serverDigest.contentEquals(localDigest)
    return UploadIdentity(attachmentId, serverDigest, adoptedFromServer = differs)
}
