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

/**
 * A room's pin as [FocusPinCoordinator] reads it: the stored pin, the `focusPin` ownership mark, and
 * whether the chat list shows the row (`lastActiveTime != 0`).
 */
data class FocusPinState(val pinned: Boolean, val focusPin: Int, val visible: Boolean = true)

/**
 * The room-table operations behind [FocusPinCoordinator] (design §1.2 / §1.3). All blocking; the
 * coordinator runs them on IO under its own lock. Every write refreshes the chat list.
 */
interface FocusPinStore {
    /** Rooms whose `focusPin` is not [ROOM_FOCUS_PIN_NONE], as roomId to mark. */
    fun ownedRooms(): Map<String, Int>

    /** The room's pin state, or null when it has no row. */
    fun pinState(gid: String): FocusPinState?

    /** Creates the room when missing and makes it visible (`lastActiveTime == 0` rooms are filtered out of the list). */
    fun ensureVisibleRoom(gid: String, now: Long)

    /** Writes `pinnedTime` and `focusPin` in one statement. */
    fun writePin(gid: String, pinnedTime: Long?, focusPin: Int)

    /** Writes `focusPin` only. */
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
