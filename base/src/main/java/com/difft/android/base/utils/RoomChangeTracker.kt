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

    
    
    
    private val createdChannel = Channel<String>(capacity = Channel.UNLIMITED)
    private val _roomCreated = MutableSharedFlow<List<String>>(
        replay = 8,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val roomCreated: SharedFlow<List<String>> = _roomCreated.asSharedFlow()

    init {
        appScope.launch {
            val buffer = mutableSetOf<Pair<String, RoomChangeType>>()

            while (true) {
                buffer.add(changeChannel.receive())

                
                delay(500)

                while (true) {
                    val result = changeChannel.tryReceive()
                    if (result.isSuccess) {
                        buffer.add(result.getOrThrow())
                    } else {
                        break
                    }
                }

                if (buffer.isNotEmpty()) {
                    val changes = buffer.map { (roomId, type) ->
                        RoomChange(roomId, type)
                    }
                    _roomChanges.tryEmit(changes)
                    buffer.clear()
                }
            }
        }

        
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
        L.d { "[Message][RoomChangeTracker] trackRoom:$roomId type:$type" }
        changeChannel.trySend(roomId to type)
    }

    
    fun trackRoomCreated(roomId: String) {
        L.i { "[Message][RoomChangeTracker] trackRoomCreated:$roomId" }
        createdChannel.trySend(roomId)
    }

    fun close() {
        changeChannel.close()
        createdChannel.close()
    }

    suspend fun trackRoomReadInfoUpdate(roomId: String) {
        L.d { "[Message][RoomChangeTracker] Emitting read info update for room: $roomId" }
        _readInfoUpdates.emit(roomId)
    }

}

data class RoomChange(
    val roomId: String,
    val type: RoomChangeType
)

enum class RoomChangeType {
    MESSAGE,       
    CONTACT,       
    GROUP_MEMBER,  
    GROUP,         
    REFRESH,       
}