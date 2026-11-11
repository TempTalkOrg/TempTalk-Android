package com.difft.android.chat.pagination

import com.difft.android.base.utils.RoomChange
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.sampleAfterFirst
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map







internal fun roomMessageChanges(roomId: String): Flow<Unit> =
    roomChangeSignals(RoomChangeTracker.roomChanges, roomId)







internal fun roomChangeSignals(
    upstream: Flow<List<RoomChange>>,
    roomId: String,
): Flow<Unit> =
    upstream
        .filter { changes -> changes.any { it.roomId == roomId && it.type == RoomChangeType.MESSAGE } }
        .sampleAfterFirst(ROOM_CHANGE_SAMPLE_PERIOD_MS)
        .map { }

private const val ROOM_CHANGE_SAMPLE_PERIOD_MS = 500L
