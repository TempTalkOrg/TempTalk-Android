package com.difft.android.chat

/** Result of splitting a loaded candidate list into render page + invisible layout anchors. */
internal data class MessageWindow<T>(
    val anchorBefore: T?,
    val pageMessages: List<T>,
    val anchorAfter: T?,
)

/**
 * Split [sortedAsc] (ascending by `systemShowTimestamp`) into the page to render plus the two
 * anchors used only for day-header / name / time display decisions (anchors are never rendered).
 *
 * Single owner for the three call sites that need it: the default first screen, the
 * failure-anchored first screen, and `jumpToMessage`. Behavior is the algorithm those sites
 * carried inline before extraction, branch for branch.
 *
 * @param forwardCount how many of the elements came from the forward (ASC) query — the caller's
 *   `expectedUnreadMessages.size` / `afterMessages.size`. When the forward query alone overflows
 *   the page, its extra element becomes the after-anchor and no before-anchor is needed. Passing
 *   `sortedAsc.size` here instead would misassign the anchors, so each call site MUST read the
 *   count off its own forward query.
 */
internal fun <T> splitMessageWindow(
    sortedAsc: List<T>,
    forwardCount: Int,
    pageSize: Int,
): MessageWindow<T> = when {
    // Not enough messages to need an anchor.
    sortedAsc.size <= pageSize -> MessageWindow(null, sortedAsc, null)
    // Forward query alone overflows the page; its last element becomes the after-anchor.
    forwardCount >= pageSize + 1 -> MessageWindow(null, sortedAsc.take(pageSize), sortedAsc.last())
    // Mixed forward/backward: first element is the before-anchor, last is the after-anchor (if any).
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
