package com.difft.android.chat


internal data class MessageWindow<T>(
    val anchorBefore: T?,
    val pageMessages: List<T>,
    val anchorAfter: T?,
)















internal fun <T> splitMessageWindow(
    sortedAsc: List<T>,
    forwardCount: Int,
    pageSize: Int,
): MessageWindow<T> = when {
    
    sortedAsc.size <= pageSize -> MessageWindow(null, sortedAsc, null)
    
    forwardCount >= pageSize + 1 -> MessageWindow(null, sortedAsc.take(pageSize), sortedAsc.last())
    
    else -> {
        val hasAfterAnchor = sortedAsc.size > pageSize + 1
        MessageWindow(
            anchorBefore = sortedAsc.first(),
            pageMessages = if (hasAfterAnchor) {
                sortedAsc.subList(1, pageSize + 1)
            } else {
                sortedAsc.subList(1, sortedAsc.size)
            },
            anchorAfter = if (hasAfterAnchor) sortedAsc.last() else null,
        )
    }
}
