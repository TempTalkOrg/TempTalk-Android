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

    suspend fun getPublicKeyInfo(forWhat: For): String?

    suspend fun updatePublicKeyInfo(forWhat: For, publicKeyInfo: String?)

    suspend fun getMessageReadPosition(forWhat: For): Long

    suspend fun updateMessageReadPosition(forWhat: For, readPosition: Long)

    suspend fun getUnreadMessageInfo(room: For): UnreadMessageInfo
}