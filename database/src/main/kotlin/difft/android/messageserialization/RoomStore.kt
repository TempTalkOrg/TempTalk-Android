package difft.android.messageserialization

import difft.android.messageserialization.unreadmessage.UnreadMessageInfo
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import java.util.Optional

interface RoomStore {
    fun updateMessageExpiry(forWhat: For, messageExpiry: Long, messageClearAnchor: Long): Completable

    fun getMessageExpiry(forWhat: For): Single<Optional<Long>>

    fun updateMuteStatus(forWhat: For, muteStatus: Int?): Completable

    fun getMuteStatus(forWhat: For): Single<Optional<Int>>

    fun updatePinnedTime(forWhat: For, pinnedTime: Long?): Completable

    fun getPinnedTime(forWhat: For): Single<Optional<Long>>

    fun getPublicKeyInfo(forWhat: For): Single<Optional<String>>

    fun updatePublicKeyInfo(forWhat: For, publicKeyInfo: String?): Completable

    fun getMessageReadPosition(forWhat: For): Single<Long>

    suspend fun updateMessageReadPosition(forWhat: For, readPosition: Long)

    fun getUnreadMessageInfo(room: For): Single<UnreadMessageInfo>
}