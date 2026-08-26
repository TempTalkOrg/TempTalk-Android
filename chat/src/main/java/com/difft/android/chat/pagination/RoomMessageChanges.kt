package com.difft.android.chat.pagination

import com.difft.android.base.utils.RoomChange
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.sampleAfterFirst
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Debounced "re-query this room's window" signal.
 *
 * Split out of [WcdbChatMessageWindowSource] so it can be exercised without loading WCDB's native
 * library: the class body builds winq expressions, this file touches no WCDB type at all.
 */
internal fun roomMessageChanges(roomId: String): Flow<Unit> =
    roomChangeSignals(RoomChangeTracker.roomChanges, roomId)

/**
 * The operator chain itself, over an injectable [upstream].
 *
 * COLD by construction — `filter` and `sampleAfterFirst` are both cold, so every `collect`
 * rebuilds the sampling state (`firstEmitted` / ticker / cached latest). That is what makes it
 * equivalent to the pre-seam code, which rebuilt the whole chain on each
 * `observerMessagesChanges()` restart, and it is why a restarted observer still gets its next
 * signal on the leading edge instead of inheriting a stale sampling window.
 */
internal fun roomChangeSignals(
    upstream: Flow<List<RoomChange>>,
    roomId: String,
): Flow<Unit> =
    upstream
        .filter { changes -> changes.any { it.roomId == roomId && it.type == RoomChangeType.MESSAGE } }
        .sampleAfterFirst(ROOM_CHANGE_SAMPLE_PERIOD_MS)
        .map { }

/** Unchanged from the pre-seam controller chain. */
private const val ROOM_CHANGE_SAMPLE_PERIOD_MS = 500L
