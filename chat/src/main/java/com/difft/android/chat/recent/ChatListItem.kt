package com.difft.android.chat.recent

import com.difft.android.chat.focus.FocusTimeFormat
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE

sealed class ListItem {
    object SearchInput : ListItem()
    data class ChatItem(val data: RoomViewData) : ListItem()

    /** The focus group's row: a chat row with the focus icon in the trailing slot and the latest previews. */
    data class FocusChat(val data: RoomViewData) : ListItem()

    /** The "Folded Groups (n)" row that stands for every other group while focus folds them. */
    data class FoldedGroups(val summary: FoldedGroupsSummary) : ListItem()

    /** Stateless list-footer singleton — the same instance is referentially equal across every
     * `submitList` call, so [isSameRow]'s `this == other` fallthrough is unconditionally true for
     * it, giving "no change" for free. */
    object E2eeFooter : ListItem()

    /** The conversation behind a chat or focus row; null for every other row. */
    fun chatItemData(): RoomViewData? = when (this) {
        is ChatItem -> data
        is FocusChat -> data
        else -> null
    }

    /** Row identity for DiffUtil: the room for conversation rows, the type for the folded row and singletons. */
    fun isSameRow(other: ListItem): Boolean = when {
        this is ChatItem && other is ChatItem -> data.roomId == other.data.roomId
        this is FocusChat && other is FocusChat -> data.roomId == other.data.roomId
        this is FoldedGroups && other is FoldedGroups -> true
        else -> this == other
    }
}

/** What the folded-groups row shows: count, summed unread, the newest group's preview and the strongest mention. */
data class FoldedGroupsSummary(
    val count: Int,
    /** Sum of the real unread counts (manual-unread marks are not counted, like the tab badge). */
    val unreadCount: Int,
    /** Some folded group carries a manual-unread mark; shown as a dot when [unreadCount] is 0. */
    val hasManualUnread: Boolean,
    /** ME when any folded group has an unread @me, else ALL when any has an unread @all. */
    val mentionType: Int = MENTIONS_TYPE_NONE,
    val latestGroupName: CharSequence? = null,
    val latestContent: CharSequence? = null,
    val lastActiveTimeText: String = "",
    val sortTime: Long = 0L,
    /** Focus end time, shown after the name as "HH:MM resume"; null while focus runs until ended by hand. */
    val resumeLabel: FocusTimeFormat.EndLabel? = null,
)
