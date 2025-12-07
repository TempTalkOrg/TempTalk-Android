package com.difft.android.base.utils

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

object RoomChangeTracker {
    private val pendingChanges = mutableSetOf<Pair<String, RoomChangeType>>()
    private val channel = Channel<Unit>(Channel.CONFLATED)

    private val _roomChanges = MutableSharedFlow<List<RoomChange>>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val roomChanges: SharedFlow<List<RoomChange>> = _roomChanges.asSharedFlow()

    private val _readInfoUpdates = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val readInfoUpdates: SharedFlow<String> = _readInfoUpdates.asSharedFlow()

    init {
        appScope.launch {
            while (true) {
                channel.receive()
                delay(500)
                sendChanges()
            }
        }
    }

    fun trackRoom(roomId: String, type: RoomChangeType) {
        L.i { "[Message][RoomChangeTracker] trackRoom:$roomId type:$type" }
        pendingChanges.add(roomId to type)
        channel.trySend(Unit)
    }

    private fun sendChanges() {
        if (pendingChanges.isEmpty()) return
        val changes = pendingChanges.map { (roomId, type) -> RoomChange(roomId, type) }
        _roomChanges.tryEmit(changes)
        pendingChanges.clear()
    }

    fun close() {
        channel.close()
    }

    suspend fun trackRoomReadInfoUpdate(roomId: String) {
        L.i { "[Message][RoomChangeTracker] Emitting read info update for room: $roomId" }
        _readInfoUpdates.emit(roomId)
    }

//    /**
//     * 强制更新所有会话
//     */
//    suspend fun forceUpdateAllRooms() {
//        try {
//            val roomIds = wcdb.room.allObjects.map { it.roomId }
//            if (roomIds.isEmpty()) return
//
//            roomIds.forEach { roomId ->
//                trackRoom(roomId, RoomChangeType.MESSAGE)
//            }
//        } catch (e: Exception) {
//            L.e { "[Message][RoomChangeTracker] Error in force update: ${e.stackTraceToString()}" }
//        }
//    }
}

data class RoomChange(
    val roomId: String,
    val type: RoomChangeType
)

enum class RoomChangeType {
    MESSAGE,       // 消息变化，需要重新查询消息预览
    CONTACT,       // 联系人变化，需要重新查询联系人信息
    GROUP_MEMBER,  // 群成员变化，需要重新查询群成员
    GROUP,         // 群组变化，需要重新查询群信息
    REFRESH,       // 只需要刷新UI，数据已更新（置顶、免打扰、已读状态、草稿等）
}