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






@Singleton
class FocusChatRooms @Inject constructor(
    private val dbRoomStore: DBRoomStore,
) {

    
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
