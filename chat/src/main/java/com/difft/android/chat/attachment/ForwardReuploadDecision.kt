package com.difft.android.chat.attachment















sealed interface ForwardLeafUpload {

    



    data object NotNeeded : ForwardLeafUpload

    



    data class FromPlaintext(val basePath: String) : ForwardLeafUpload

    




    data class FromStoredCiphertext(val basePath: String) : ForwardLeafUpload

    



    data object NoLocalBytes : ForwardLeafUpload
}


















fun forwardLeafUpload(
    authorityId: Long,
    key: ByteArray?,
    fileName: String?,
    basePath: String,
    hasPlaintext: Boolean,
    hasCiphertext: Boolean
): ForwardLeafUpload {
    if (authorityId != 0L) return ForwardLeafUpload.NotNeeded
    if (key == null || key.isEmpty()) return ForwardLeafUpload.NotNeeded
    if (fileName.isNullOrEmpty()) return ForwardLeafUpload.NoLocalBytes
    return when {
        hasCiphertext -> ForwardLeafUpload.FromStoredCiphertext(basePath)
        hasPlaintext -> ForwardLeafUpload.FromPlaintext(basePath)
        else -> ForwardLeafUpload.NoLocalBytes
    }
}


data class LeafBytes(val hasPlaintext: Boolean, val hasCiphertext: Boolean)



















fun forwardLeafUpload(
    authorityId: Long,
    key: ByteArray?,
    fileName: String?,
    basePath: String,
    bytesAt: (String) -> LeafBytes,
    sendSourceBasePath: () -> String?
): ForwardLeafUpload {
    val own = bytesAt(basePath)
    val direct = forwardLeafUpload(authorityId, key, fileName, basePath, own.hasPlaintext, own.hasCiphertext)
    if (direct !is ForwardLeafUpload.NoLocalBytes) return direct
    
    
    if (fileName.isNullOrEmpty()) return direct
    val source = sendSourceBasePath() ?: return direct
    val sourceBytes = bytesAt(source)
    return forwardLeafUpload(authorityId, key, fileName, source, sourceBytes.hasPlaintext, sourceBytes.hasCiphertext)
}
