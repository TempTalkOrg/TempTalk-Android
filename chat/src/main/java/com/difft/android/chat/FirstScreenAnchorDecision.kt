package com.difft.android.chat







internal sealed interface FirstScreenAnchor {
    
    data object FromReadPosition : FirstScreenAnchor

    
    data object AtFailedMessage : FirstScreenAnchor
}

















internal fun decideFirstScreenAnchor(
    firstFailedTs: Long?,
    firstUnreadOthersTs: Long?,
): FirstScreenAnchor {
    if (firstFailedTs == null) return FirstScreenAnchor.FromReadPosition
    
    
    
    
    
    return if (firstUnreadOthersTs == null || firstFailedTs < firstUnreadOthersTs) {
        FirstScreenAnchor.AtFailedMessage
    } else {
        FirstScreenAnchor.FromReadPosition
    }
}
