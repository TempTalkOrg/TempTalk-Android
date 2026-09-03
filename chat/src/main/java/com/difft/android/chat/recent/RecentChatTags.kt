package com.difft.android.chat.recent

import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT
import difft.android.messageserialization.model.MENTIONS_TYPE_ALL
import difft.android.messageserialization.model.MENTIONS_TYPE_ME
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED

/**
 * Chat-list preview tags. Declaration order IS display order AND priority order (highest first).
 * Figma: 40bJJgv2zx4UJOzIk6Hu9M node 17584:10715.
 *
 * [droppable] tags are hidden right-to-left when horizontal space runs out; CRITICAL_ALERT and
 * SEND_FAILED are never dropped (node 17584:10718).
 */
enum class ChatListTag(val droppable: Boolean) {
    CRITICAL_ALERT(droppable = false),
    SEND_FAILED(droppable = false),
    MENTION(droppable = true),
    DRAFT(droppable = true),
}

data class TagSegment(val tag: ChatListTag, val text: String)

/** Localized tag labels. Brackets live in the string resources, not here. */
data class ChatListTagLabels(
    val criticalAlert: String,
    val sendFailed: String,
    val atYou: String,
    val atAll: String,
    val draft: String,
)

/** Separator from the design (node 17584:10716). Punctuation, not translatable. */
const val TAG_SEPARATOR = " · "

/**
 * Builds every tag the room currently earns, always in [ChatListTag] declaration order. Returns
 * an empty list when the room earns none (caller hides the view).
 */
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
    // Only FAILED renders a tag. SENDING is reserved for a future "sending" tag: adding it here is
    // the only change needed — storage and write paths already carry the value.
    if (sendStatus == ROOM_SEND_STATUS_FAILED) {
        add(TagSegment(ChatListTag.SEND_FAILED, labels.sendFailed))
    }
    when (mentionType) {
        MENTIONS_TYPE_ME -> add(TagSegment(ChatListTag.MENTION, labels.atYou))
        MENTIONS_TYPE_ALL -> add(TagSegment(ChatListTag.MENTION, labels.atAll))
    }
    if (hasDraft) add(TagSegment(ChatListTag.DRAFT, labels.draft))
}

/**
 * Joins the tag run with [TAG_SEPARATOR]. Returns a plain [CharSequence]: every segment shares one
 * colour, supplied by `android:textColor` on the target TextView, so no per-segment spans are built.
 */
fun joinTags(tags: List<TagSegment>): CharSequence = tags.joinToString(TAG_SEPARATOR) { it.text }

/**
 * Drops whole tags — never characters — until the joined text fits [availableWidthPx], lowest
 * priority first. Non-droppable tags are kept even when they overflow; the caller's
 * `ellipsize="end"` is the last resort.
 *
 * At most 2 drops (2 droppable tags) => at most 3 [measureText] calls per bind.
 * [availableWidthPx] may be <= 0 on very narrow rows; the loop still terminates.
 *
 * [measureText] is a plain function rather than a `TextPaint` so the whole degradation rule stays
 * framework-free and unit-testable; the caller passes `textView.paint::measureText`.
 */
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

// --- Fixed geometry, all traced to chat_fragment_recent_chat_list_item.xml ---
private const val AVATAR_BLOCK_DP = 64          // avatar frame 48 + its marginStart 16
private const val TEXT_COLUMN_MARGIN_DP = 28    // text column marginStart 12 + marginEnd 16
private const val TAG_MARGIN_END_DP = 4         // textview_at marginEnd
private const val DETAIL_MARGIN_END_DP = 16     // textview_detail marginEnd (applies even when the badge is GONE)
private const val CALL_BAR_RESERVE_DP = 88      // call_bar_duration marginEnd 16 + paddingH 16 + widest "1:02:33" @12sp
private const val BADGE_RESERVE_DP = 24         // textview_missed_number minWidth 18 + padding 3 per side, "99+" @10sp
private const val BADGE_RESERVE_LARGE_DP = 30   // same at 12sp (see updateTextSizes)
private const val PREVIEW_FLOOR_DP = 56         // ~4 CJK / ~7 latin chars @14sp
private const val PREVIEW_FLOOR_LARGE_DP = 84   // 56 * 21/14
private const val SAFETY_SLACK_DP = 4           // emoji width variance across ROMs
// imageview_sending 16 + its marginEnd 4. Single tier on purpose (like CALL_BAR_RESERVE_DP,
// unlike the two-tier badge): the icon is fixed-size regardless of text scale.
private const val SENDING_ICON_RESERVE_DP = 20

/**
 * Width the tag TextView may occupy before the preview stops being readable. Deliberately
 * pessimistic: over-reserving drops one more tag (cosmetic), while under-reserving squeezes the
 * preview to zero width. May return <= 0 on very narrow rows — [selectVisibleTags] handles that.
 */
fun computeTagAvailableWidthPx(
    rowWidthPx: Int,
    density: Float,
    hasUnreadBadge: Boolean,
    hasCallBar: Boolean,
    isLargerText: Boolean,
    hasSendingIcon: Boolean = false,
): Float {
    fun dp(v: Int) = v * density
    val reserved = dp(AVATAR_BLOCK_DP) + dp(TEXT_COLUMN_MARGIN_DP) +
        dp(TAG_MARGIN_END_DP) + dp(DETAIL_MARGIN_END_DP) + dp(SAFETY_SLACK_DP) +
        dp(if (isLargerText) PREVIEW_FLOOR_LARGE_DP else PREVIEW_FLOOR_DP) +
        (if (hasCallBar) dp(CALL_BAR_RESERVE_DP) else 0f) +
        (if (hasUnreadBadge) dp(if (isLargerText) BADGE_RESERVE_LARGE_DP else BADGE_RESERVE_DP) else 0f) +
        (if (hasSendingIcon) dp(SENDING_ICON_RESERVE_DP) else 0f)
    return rowWidthPx - reserved
}

/**
 * Row width, most-accurate source first: a laid-out (recycled) itemView, then the RecyclerView
 * width captured at onCreateViewHolder, then the display width. The order matters on tablets /
 * split-screen, where the list is narrower than the screen.
 */
fun resolveRowWidthPx(itemViewWidthPx: Int, containerWidthPx: Int, displayWidthPx: Int): Int =
    itemViewWidthPx.takeIf { it > 0 } ?: containerWidthPx.takeIf { it > 0 } ?: displayWidthPx

/** Preview text color: emphasized only for rooms with unread messages that are not muted. */
fun detailColorRes(unreadMessageNum: Int, isMuted: Boolean): Int =
    if (unreadMessageNum != 0 && !isMuted) com.difft.android.base.R.color.t_primary
    else com.difft.android.base.R.color.t_third
