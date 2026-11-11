package com.difft.android.chat.focus

import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.chat.recent.FocusChatRooms
import com.tencent.wcdb.base.Value
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.ROOM_FOCUS_PIN_NONE
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton





data class FocusPinState(val pinned: Boolean, val focusPin: Int, val visible: Boolean = true)





interface FocusPinStore {
    
    fun ownedRooms(): Map<String, Int>

    
    fun pinState(gid: String): FocusPinState?

    
    fun ensureVisibleRoom(gid: String, now: Long)

    
    fun writePin(gid: String, pinnedTime: Long?, focusPin: Int)

    
    fun writeMark(gid: String, focusPin: Int)
}

@Singleton
class DbFocusPinStore @Inject constructor(
    private val focusChatRooms: FocusChatRooms,
) : FocusPinStore {

    override fun ownedRooms(): Map<String, Int> =
        wcdb.room.getAllObjects(DBRoomModel.focusPin.notEq(ROOM_FOCUS_PIN_NONE)).associate { it.roomId to it.focusPin }

    override fun pinState(gid: String): FocusPinState? =
        wcdb.room.getFirstObject(DBRoomModel.roomId.eq(gid))?.let { room ->
            FocusPinState(pinned = (room.pinnedTime ?: 0L) > 0L, focusPin = room.focusPin, visible = room.lastActiveTime != 0L)
        }

    override fun ensureVisibleRoom(gid: String, now: Long) = focusChatRooms.ensureVisibleRoom(gid, now)

    override fun writePin(gid: String, pinnedTime: Long?, focusPin: Int) {
        wcdb.room.updateRow(
            arrayOf(Value(pinnedTime), Value(focusPin)),
            arrayOf(DBRoomModel.pinnedTime, DBRoomModel.focusPin),
            DBRoomModel.roomId.eq(gid),
        )
        RoomChangeTracker.trackRoom(gid, RoomChangeType.REFRESH)
    }

    override fun writeMark(gid: String, focusPin: Int) {
        wcdb.room.updateValue(Value(focusPin), DBRoomModel.focusPin, DBRoomModel.roomId.eq(gid))
        RoomChangeTracker.trackRoom(gid, RoomChangeType.REFRESH)
    }
}
