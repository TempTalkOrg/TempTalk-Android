package difft.android.messageserialization

import difft.android.messageserialization.unreadmessage.UnreadMessageInfo
import java.util.Optional

interface RoomStore {
    suspend fun updateMessageExpiry(forWhat: For, messageExpiry: Long, messageClearAnchor: Long)

    suspend fun getMessageExpiry(forWhat: For): Optional<Long>

    suspend fun updateMuteStatus(forWhat: For, muteStatus: Int?)

    suspend fun getMuteStatus(forWhat: For): Optional<Int>

    suspend fun updatePinnedTime(forWhat: For, pinnedTime: Long?)

    suspend fun getPinnedTime(forWhat: For): Optional<Long>

    suspend fun getMessageReadPosition(forWhat: For): Long

    suspend fun updateMessageReadPosition(forWhat: For, readPosition: Long)

    suspend fun getUnreadMessageInfo(room: For): UnreadMessageInfo

    /** Manual mark-unread state as (flag, markUnreadAt); (CLEAR, 0) when the room has no row. */
    suspend fun getMarkUnread(forWhat: For): Pair<Int, Long>

    /** Stores the manual mark-unread state and refreshes the conversation list. */
    suspend fun updateMarkUnread(forWhat: For, markUnreadFlag: Int, markUnreadAt: Long)
}