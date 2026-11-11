package com.difft.android.chat.attachment












sealed interface AttachmentRowTarget {
    
    data class ByLocalId(val localId: String) : AttachmentRowTarget

    
    data class ByIdAndMessage(val attachmentId: String, val messageId: String) : AttachmentRowTarget

    
    data class ById(val attachmentId: String) : AttachmentRowTarget
}


fun attachmentRowTarget(localId: String?, attachmentId: String, messageId: String?): AttachmentRowTarget = when {
    !localId.isNullOrEmpty() -> AttachmentRowTarget.ByLocalId(localId)
    !messageId.isNullOrEmpty() -> AttachmentRowTarget.ByIdAndMessage(attachmentId, messageId)
    else -> AttachmentRowTarget.ById(attachmentId)
}











fun <T> adoptableLocalId(
    candidates: List<T>,
    localIdOf: (T) -> String?,
    synthesizedLocalIdOf: (T) -> String
): String? {
    val row = candidates.singleOrNull() ?: return null
    return localIdOf(row)?.takeIf { it.isNotEmpty() } ?: synthesizedLocalIdOf(row)
}














fun attachmentRowKey(rowLocalId: String?, jobLocalId: String?, synthesizedLocalId: String): String =
    rowLocalId?.takeIf { it.isNotEmpty() }
        ?: jobLocalId?.takeIf { it.isNotEmpty() }
        ?: synthesizedLocalId










fun <T> pickLegacyAttachmentRow(
    candidates: List<T>,
    legacyKey: String,
    messageIdOf: (T) -> String?,
    authorityIdOf: (T) -> Long?
): T? {
    if (candidates.size <= 1) return candidates.firstOrNull()
    return candidates.firstOrNull { messageIdOf(it) == legacyKey }
        ?: candidates.firstOrNull { authorityIdOf(it)?.toString() == legacyKey }
        ?: candidates.first()
}
