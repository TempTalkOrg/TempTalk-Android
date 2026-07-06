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
    // Channel 来接收房间变更事件，UNLIMITED 确保不会丢失事件
    private val changeChannel = Channel<Pair<String, RoomChangeType>>(capacity = Channel.UNLIMITED)

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

    // Newly created (incl. re-created after delete) room ids, batched over 500ms.
    // Consumers refetch the conversation's server config so settings survive delete + recreate.
    // replay keeps recent batches so a consumer subscribing slightly late at startup is not missed.
    private val createdChannel = Channel<String>(capacity = Channel.UNLIMITED)
    private val _roomCreated = MutableSharedFlow<List<String>>(
        replay = 8,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val roomCreated: SharedFlow<List<String>> = _roomCreated.asSharedFlow()

    init {
        appScope.launch {
            // 用于批量收集变更的缓冲区，Set 自动去重
            val buffer = mutableSetOf<Pair<String, RoomChangeType>>()

            while (true) {
                // 阻塞等待第一个变更事件
                buffer.add(changeChannel.receive())

                // 等待 500ms，在此期间继续收集更多变更
                delay(500)

                // 非阻塞地收集这段时间内的所有其他变更
                while (true) {
                    val result = changeChannel.tryReceive()
                    if (result.isSuccess) {
                        buffer.add(result.getOrThrow())
                    } else {
                        break
                    }
                }

                // 批量发送所有收集到的变更
                if (buffer.isNotEmpty()) {
                    val changes = buffer.map { (roomId, type) ->
                        RoomChange(roomId, type)
                    }
                    _roomChanges.tryEmit(changes)
                    buffer.clear()
                }
            }
        }

        // Batch room-created events (same 500ms coalescing as above).
        appScope.launch {
            val buffer = mutableSetOf<String>()
            while (true) {
                buffer.add(createdChannel.receive())
                delay(500)
                while (true) {
                    val result = createdChannel.tryReceive()
                    if (result.isSuccess) {
                        buffer.add(result.getOrThrow())
                    } else {
                        break
                    }
                }
                if (buffer.isNotEmpty()) {
                    _roomCreated.tryEmit(buffer.toList())
                    buffer.clear()
                }
            }
        }
    }

    fun trackRoom(roomId: String, type: RoomChangeType) {
        L.i { "[Message][RoomChangeTracker] trackRoom:$roomId type:$type" }
        // 直接发送到 Channel，无需加锁
        changeChannel.trySend(roomId to type)
    }

    /** Mark a room as newly created (incl. re-created after delete), to refetch its server config. */
    fun trackRoomCreated(roomId: String) {
        L.i { "[Message][RoomChangeTracker] trackRoomCreated:$roomId" }
        createdChannel.trySend(roomId)
    }

    fun close() {
        changeChannel.close()
        createdChannel.close()
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