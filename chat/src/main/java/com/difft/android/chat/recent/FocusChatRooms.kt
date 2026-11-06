package com.difft.android.chat.recent

import com.difft.android.base.log.lumberjack.L
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.tencent.wcdb.base.Value
import difft.android.messageserialization.For
import kotlinx.coroutines.CancellationException
import org.difft.app.database.WCDBUpdateService
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.previewContent
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DB access the chat list needs for the focus group only (design §3/§4): its latest previews for
 * the focus card, a stand-in row when it has no visible room row, and making that room real when
 * the user pins it. All blocking; callers run them on IO, never from a bind.
 */
@Singleton
class FocusChatRooms @Inject constructor(
    private val dbRoomStore: DBRoomStore,
) {

    /** The focus card's previews (`sender: content`, the room preview format), oldest first. */
    fun previewLines(gid: String): List<String> = try {
        WCDBUpdateService.latestPreviewMessages(gid, FOCUS_CARD_LINES.toLong())
            .map { it.previewContent() }
            .filter { it.isNotEmpty() }
            .reversed()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        L.w { "[ChatList] focus previews failed error=${e.javaClass.simpleName}" }
        emptyList()
    }

    /**
     * A row for the focus group built from its group (and room, when one exists with
     * `lastActiveTime == 0`), with an empty preview; null when the group is not stored or not
     * active. Never written.
     */
    fun synthesize(gid: String): RoomViewData? = try {
        val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(gid))?.takeIf { it.status == 0 }
        if (group == null) {
            null
        } else {
            val room = wcdb.room.getFirstObject(DBRoomModel.roomId.eq(gid))
            RoomViewData(
                roomId = gid,
                type = RoomViewData.Type.Group,
                roomName = room?.roomName?.takeIf { it.isNotEmpty() } ?: group.name,
                roomAvatarJson = room?.roomAvatarJson ?: group.avatar,
                unreadMessageNum = room?.unreadMessageNum ?: 0,
                muteStatus = room?.muteStatus ?: 0,
                pinnedTime = room?.pinnedTime,
                messageExpiry = room?.messageExpiry,
                groupMembersNumber = room?.groupMembersNumber ?: 0,
                effectiveMuted = false,
                isSynthesized = true,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        L.w { "[ChatList] focus row synthesis failed error=${e.javaClass.simpleName}" }
        null
    }

    /**
     * Before a user pin on a synthesized row: creates the room when missing and makes it visible
     * (`lastActiveTime == 0` rooms are filtered out of the list), so the pin outlives the focus.
     */
    fun ensureVisibleRoom(gid: String, now: Long) {
        dbRoomStore.createRoomIfNotExist(For.Group(gid))
        wcdb.room.updateValue(
            Value(now),
            DBRoomModel.lastActiveTime,
            DBRoomModel.roomId.eq(gid).and(DBRoomModel.lastActiveTime.eq(0L)),
        )
        L.i { "[ChatList] focus room made visible for pin" }
    }
}
