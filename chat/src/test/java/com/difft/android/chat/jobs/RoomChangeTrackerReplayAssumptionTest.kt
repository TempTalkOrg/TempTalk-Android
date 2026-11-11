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









class RoomChangeTrackerReplayAssumptionTest {

    private fun trackerLikeFlow() = MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @Test
    fun `emission with no subscriber is dropped and never replayed`() = runTest {
        val flow = trackerLikeFlow()

        
        
        
        assertTrue(flow.tryEmit(1))
        assertEquals(0, flow.subscriptionCount.value)

        
        val received = withTimeoutOrNull(200) { flow.first() }

        assertNull(received)
    }

    @Test
    fun `emission is delivered once a subscriber is already registered`() = runTest {
        val flow = trackerLikeFlow()
        val received = mutableListOf<Int>()
        val job = launch { flow.collect { received.add(it) } }

        
        while (flow.subscriptionCount.value == 0) yield()
        flow.emit(7)
        yield()

        assertEquals(listOf(7), received)
        job.cancel()
    }
}
