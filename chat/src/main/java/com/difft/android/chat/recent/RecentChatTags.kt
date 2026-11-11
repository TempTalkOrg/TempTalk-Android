package com.difft.android.chat.recent

import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT
import difft.android.messageserialization.model.MENTIONS_TYPE_ALL
import difft.android.messageserialization.model.MENTIONS_TYPE_ME
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED






enum class ChatListTag(val droppable: Boolean) {
    CRITICAL_ALERT(droppable = false),
    SEND_FAILED(droppable = false),
    MENTION(droppable = true),
    DRAFT(droppable = true),
}

data class TagSegment(val tag: ChatListTag, val text: String)


data class ChatListTagLabels(
    val criticalAlert: String,
    val sendFailed: String,
    val atYou: String,
    val atAll: String,
    val draft: String,
)


const val TAG_SEPARATOR = " · "





fun buildTagSegments(
    criticalAlertType: Int,
    sendStatus: Int,
    mentionType: Int,
    hasDraft: Boolean,
    labels: ChatListTagLabels,
): List<TagSegment> = buildList {
    if (criticalAlertType == CRITICAL_ALERT_TYPE_ALERT) {
        add(TagSegment(ChatListTag.CRITICAL_ALERT, labels.criticalAlert))
    }
    
    if (sendStatus == ROOM_SEND_STATUS_FAILED) {
        add(TagSegment(ChatListTag.SEND_FAILED, labels.sendFailed))
    }
    when (mentionType) {
        MENTIONS_TYPE_ME -> add(TagSegment(ChatListTag.MENTION, labels.atYou))
        MENTIONS_TYPE_ALL -> add(TagSegment(ChatListTag.MENTION, labels.atAll))
    }
    if (hasDraft) add(TagSegment(ChatListTag.DRAFT, labels.draft))
}





fun joinTags(tags: List<TagSegment>): CharSequence = tags.joinToString(TAG_SEPARATOR) { it.text }












fun selectVisibleTags(
    tags: List<TagSegment>,
    availableWidthPx: Float,
    measureText: (String) -> Float,
): List<TagSegment> {
    var candidate = tags
    while (measureText(joinTags(candidate).toString()) > availableWidthPx) {
        val victim = candidate.lastOrNull { it.tag.droppable } ?: break
        candidate = candidate - victim
    }
    return candidate
}


private const val AVATAR_BLOCK_DP = 68          
private const val TEXT_COLUMN_MARGIN_DP = 28    
private const val TAG_MARGIN_END_DP = 4         
private const val DETAIL_MARGIN_END_DP = 16     
private const val BADGE_RESERVE_DP = 24         
private const val BADGE_RESERVE_LARGE_DP = 30   
private const val PREVIEW_FLOOR_DP = 56         
private const val PREVIEW_FLOOR_LARGE_DP = 84   
private const val SAFETY_SLACK_DP = 4           

private const val SENDING_ICON_RESERVE_DP = 20
private const val PINNED_ICON_RESERVE_DP = 18    






fun computeTagAvailableWidthPx(
    rowWidthPx: Int,
    density: Float,
    hasUnreadBadge: Boolean,
    isLargerText: Boolean,
    hasSendingIcon: Boolean = false,
    
    hasPinnedIcon: Boolean = false,
): Float {
    fun dp(v: Int) = v * density
    val reserved = dp(AVATAR_BLOCK_DP) + dp(TEXT_COLUMN_MARGIN_DP) +
        dp(TAG_MARGIN_END_DP) + dp(DETAIL_MARGIN_END_DP) + dp(SAFETY_SLACK_DP) +
        dp(if (isLargerText) PREVIEW_FLOOR_LARGE_DP else PREVIEW_FLOOR_DP) +
        (if (hasUnreadBadge) dp(if (isLargerText) BADGE_RESERVE_LARGE_DP else BADGE_RESERVE_DP) else 0f) +
        (if (hasSendingIcon) dp(SENDING_ICON_RESERVE_DP) else 0f) +
        (if (hasPinnedIcon) dp(PINNED_ICON_RESERVE_DP) else 0f)
    return rowWidthPx - reserved
}






fun resolveRowWidthPx(itemViewWidthPx: Int, containerWidthPx: Int, displayWidthPx: Int): Int =
    itemViewWidthPx.takeIf { it > 0 } ?: containerWidthPx.takeIf { it > 0 } ?: displayWidthPx


fun detailColorRes(unreadMessageNum: Int, isMuted: Boolean): Int =
    if (unreadMessageNum != 0 && !isMuted) com.difft.android.base.R.color.t_primary
    else com.difft.android.base.R.color.t_third
