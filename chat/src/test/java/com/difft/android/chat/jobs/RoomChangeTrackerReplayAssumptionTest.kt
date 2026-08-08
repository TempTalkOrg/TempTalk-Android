package com.difft.android.chat.jobs

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Framework-assumption guard for the decision in [WcdbJobStorage.sweepStaleSendingMessages] to
 * write `room.sendStatus` DIRECTLY instead of relying on `RoomChangeTracker.trackRoom`.
 *
 * `RoomChangeTracker.roomChanges` is a `MutableSharedFlow(replay = 0, extraBufferCapacity = 64,
 * onBufferOverflow = DROP_OLDEST)`. The sweep runs from `Application.onCreate`, while the only
 * consumer (`WCDBUpdateService.updatingRooms`) registers later, from `IndexActivity`. With
 * `replay = 0` an emission made before any subscriber exists is dropped outright — so a
 * notification-only design would make the conversation-list tag a startup race.
 *
 * The `trackRoom` call kept in the sweep is a best-effort UI nudge for an already-open list, not
 * the correctness path. This test exists so a future "simplification" back to notification-only
 * fails here instead of shipping an intermittently missing tag.
 *
 * The flow is rebuilt locally with the same parameters rather than driving the real object: the
 * real one is a process-wide singleton with an `appScope` batching loop, and what is being pinned
 * is the kotlinx contract for those parameters.
 *
 * Covers T3-9.
 */
class RoomChangeTrackerReplayAssumptionTest {

    private fun trackerLikeFlow() = MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @Test
    fun `emission with no subscriber is dropped and never replayed`() = runTest {
        val flow = trackerLikeFlow()

        // Emitted while nobody is collecting — exactly the sweep-before-IndexActivity situation.
        // `tryEmit` still reports success (the buffer accepted it), which is what makes this easy
        // to mistake for a delivered event.
        assertTrue(flow.tryEmit(1))
        assertEquals(0, flow.subscriptionCount.value)

        // Subscribing afterwards sees nothing: replay = 0 keeps no history.
        val received = withTimeoutOrNull(200) { flow.first() }

        assertNull(received)
    }

    @Test
    fun `emission is delivered once a subscriber is already registered`() = runTest {
        val flow = trackerLikeFlow()
        val received = mutableListOf<Int>()
        val job = launch { flow.collect { received.add(it) } }

        // Let the collector register before emitting — the ONLY case notification-only works.
        while (flow.subscriptionCount.value == 0) yield()
        flow.emit(7)
        yield()

        assertEquals(listOf(7), received)
        job.cancel()
    }
}
