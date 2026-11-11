package com.difft.android.chat.media





enum class AttachmentUriIdentity {
    
    LOCAL_ID,

    
    MESSAGE_ID,

    
    AUTHORITY_ID,

    




    SYNTHESIZED_LOCAL_ID
}


private fun isUuidShaped(key: String): Boolean =
    key.length == 36 && key[8] == '-' && key[13] == '-' && key[18] == '-' && key[23] == '-' &&
        key.all { it == '-' || (it in '0'..'9') || (it in 'a'..'f') || (it in 'A'..'F') }






fun <T> resolveAttachmentRowByUriKey(
    key: String,
    fileName: String,
    fileNameOf: (T) -> String?,
    synthesizedLocalIdOf: (T) -> String? = { null },
    lookup: (AttachmentUriIdentity, String) -> List<T>
): T? {
    fun pick(rows: List<T>): T? = rows.firstOrNull { fileNameOf(it) == fileName } ?: rows.firstOrNull()

    if (key.isEmpty()) return null
    pick(lookup(AttachmentUriIdentity.LOCAL_ID, key))?.let { return it }
    pick(lookup(AttachmentUriIdentity.MESSAGE_ID, key))?.let { return it }
    
    if (key.toLongOrNull() != null) return pick(lookup(AttachmentUriIdentity.AUTHORITY_ID, key))
    
    
    if (!isUuidShaped(key)) return null
    return lookup(AttachmentUriIdentity.SYNTHESIZED_LOCAL_ID, key)
        .firstOrNull { synthesizedLocalIdOf(it) == key }
}
