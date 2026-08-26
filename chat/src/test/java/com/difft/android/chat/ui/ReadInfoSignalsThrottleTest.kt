package com.difft.android.chat.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The read-info trigger after it gained the same sampling gate the message-change seam has.
 *
 * The collector under test re-reads the full read-info list from the database on every signal, so
 * the property that matters is not "every signal is delivered" but "the last state is": a burst
 * costs one leading reload plus one trailing reload, and the trailing one observes whatever the
 * database held after the last update.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadInfoSignalsThrottleTest {

    @Test
    fun `a burst inside one window costs a leading and a trailing reload`() = runTest {
        val upstream = MutableSharedFlow<String>(extraBufferCapacity = BUFFER)
        // Stands in for `_readInfoList.value = wcdb.getReadInfoList(roomId)`: each reload reads
        // whatever the database holds at that moment, which is what makes sampling lossless here.
        var dbGeneration = 0
        val reloads = mutableListOf<Int>()
        readInfoSignals(upstream, ROOM_ID).onEach { reloads += dbGeneration }.launchIn(backgroundScope)
        upstream.subscriptionCount.first { it > 0 }

        dbGeneration = 1
        upstream.emit(ROOM_ID)
        runCurrent()
        assertEquals("the first signal reloads on the leading edge", listOf(1), reloads)

        repeat(BURST) {
            dbGeneration += 1
            upstream.emit(ROOM_ID)
        }
        runCurrent()
        assertEquals("the burst is sampled, not replayed", listOf(1), reloads)

        advanceTimeBy(READ_INFO_SAMPLE_PERIOD_MS + 1)
        runCurrent()

        assertEquals(
            "one trailing reload, carrying the state left by the last update",
            listOf(1, dbGeneration),
            reloads,
        )
    }

    @Test
    fun `read info updates for other rooms never reload`() = runTest {
        val upstream = MutableSharedFlow<String>(extraBufferCapacity = BUFFER)
        val reloads = mutableListOf<Unit>()
        readInfoSignals(upstream, ROOM_ID).onEach { reloads += it }.launchIn(backgroundScope)
        upstream.subscriptionCount.first { it > 0 }

        upstream.emit("other-room")
        advanceTimeBy(READ_INFO_SAMPLE_PERIOD_MS + 1)
        runCurrent()

        assertEquals(0, reloads.size)
    }

    private companion object {
        const val ROOM_ID = "room"
        const val BURST = 20
        const val BUFFER = 64
    }
}
