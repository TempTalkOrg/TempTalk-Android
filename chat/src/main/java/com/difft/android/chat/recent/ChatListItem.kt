package com.difft.android.chat.recent

import com.difft.android.chat.focus.FocusTimeFormat
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE

sealed class ListItem {
    object SearchInput : ListItem()
    data class ChatItem(val data: RoomViewData) : ListItem()

    
    data class FocusChat(val data: RoomViewData) : ListItem()

    
    data class FoldedGroups(val summary: FoldedGroupsSummary) : ListItem()

    


    object E2eeFooter : ListItem()

    
    fun chatItemData(): RoomViewData? = when (this) {
        is ChatItem -> data
        is FocusChat -> data
        else -> null
    }

    
    fun isSameRow(other: ListItem): Boolean = when {
        this is ChatItem && other is ChatItem -> data.roomId == other.data.roomId
        this is FocusChat && other is FocusChat -> data.roomId == other.data.roomId
        this is FoldedGroups && other is FoldedGroups -> true
        else -> this == other
    }
}


data class FoldedGroupsSummary(
    val count: Int,
    
    val unreadCount: Int,
    
    val hasManualUnread: Boolean,
    
    val mentionType: Int = MENTIONS_TYPE_NONE,
    val latestGroupName: CharSequence? = null,
    val latestContent: CharSequence? = null,
    val lastActiveTimeText: String = "",
    val sortTime: Long = 0L,
    
    val resumeLabel: FocusTimeFormat.EndLabel? = null,
)
