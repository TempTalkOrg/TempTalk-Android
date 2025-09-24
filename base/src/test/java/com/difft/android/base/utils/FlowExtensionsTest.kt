package com.difft.android.base.utils

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.time.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class FlowExtensionsTest {

    @Test
    fun `first element emitted immediately and later sampled`() = runTest {
        val emitted = mutableListOf<Int>()
        val testFlow = flow {
            delay(30)
            emit(1)
            delay(40)
            emit(2)
            delay(50)
            emit(3)
        }
        val job = launch {
            testFlow.sampleAfterFirst(100).collect { emitted.add(it) }
        }
        // Advance time to pass the first element
        testScheduler.advanceTimeBy(40)
        assertEquals(listOf(1), emitted)
        // Advance time to emmit the second element
        testScheduler.advanceTimeBy(40)
        assertEquals(listOf(1), emitted)

        // Advance time to pass the sampling window
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        assertEquals(listOf(1, 3), emitted)
        job.cancel()
    }

    @Test
    fun `skip leading predicate-false elements until predicate-true arrives`() = runTest {
        val emitted = mutableListOf<List<Int>>()
        val testFlow = flow {
            emit(emptyList<Int>())
            emit(emptyList<Int>())
            emit(listOf(1)) // predicate true
        }
        val job = launch {
            testFlow.sampleAfterFirst(100) { it.isNotEmpty() }.collect { emitted.add(it) }
        }
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        val expectedFirst: List<List<Int>> = listOf(listOf(1))
        assertEquals(expectedFirst, emitted)
        job.cancel()
    }

    @Test
    fun `emit predicate-false value after first sampling window if no predicate-true value`() = runTest {
        val emitted = mutableListOf<List<Int>>()
        val testFlow = flow {
            emit(emptyList<Int>())
            // no further emissions
        }
        val job = launch {
            testFlow.sampleAfterFirst(100) { it.isNotEmpty() }.collect { emitted.add(it) }
        }
        // advance just under 100ms to ensure no emission yet
        testScheduler.advanceTimeBy(99)
        testScheduler.runCurrent()
        val expectedEmpty: List<List<Int>> = emptyList()
        assertEquals(expectedEmpty, emitted)

        // advance past the sampling window so cached empty list is sent
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        val expectedAfterWindow: List<List<Int>> = listOf(listOf<Int>())
        assertEquals(expectedAfterWindow, emitted)
        job.cancel()
    }

    @Test
    fun `subsequent emissions are spaced by period`() = runTest {
        val emitted = mutableListOf<Int>()
        val shared = kotlinx.coroutines.flow.MutableSharedFlow<Int>()

        val job = launch {
            shared.sampleAfterFirst(100).collect { emitted.add(it) }
        }

        // ensure collector active
        testScheduler.runCurrent()

        shared.emit(1) // first emit
        testScheduler.runCurrent()
        assertEquals(listOf(1), emitted)

        testScheduler.advanceTimeBy(50)
        shared.emit(2) // within window, should be delayed
        testScheduler.runCurrent()
        assertEquals(listOf(1), emitted)

        testScheduler.advanceTimeBy(50) // reach 100ms
        testScheduler.runCurrent()
        assertEquals(listOf(1, 2), emitted)

        // multiple emits inside new window
        shared.emit(3)
        shared.emit(4)
        testScheduler.advanceTimeBy(99)
        testScheduler.runCurrent()
        assertEquals(listOf(1, 2), emitted)

        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(listOf(1, 2, 4), emitted)

        job.cancel()
    }

    @Test
    fun `emitImmediatelyIfNewElementArrivesAfterSilentSamplingPeriod`() = runTest {
        val emitted = mutableListOf<Int>()
        val periodMillis = 100L
        // Using MutableSharedFlow to control emissions precisely
        val sourceFlow = kotlinx.coroutines.flow.MutableSharedFlow<Int>()

        val job = launch {
            sourceFlow.sampleAfterFirst(periodMillis).collect { emitted.add(it) }
        }

        // Ensure collector is active
        testScheduler.runCurrent()

        // 1. Initial element: emitted immediately
        sourceFlow.emit(1)
        testScheduler.runCurrent()
        assertEquals(listOf(1), emitted, "Test Step 1: First element should be emitted immediately")

        // 2. Advance time well beyond the first sampling period (e.g., 150ms).
        //    During this time, no new elements arrive from sourceFlow.
        //    The ticker would have run at 100ms, but 'latest' was null, so no emission.
        testScheduler.advanceTimeBy(periodMillis + 50L) // Advance to 150ms
        testScheduler.runCurrent()
        assertEquals(listOf(1), emitted, "Test Step 2: No new emissions after first period if source was silent")

        // 3. New element arrives *after* this silent sampling period has passed.
        //    This element (2) should be emitted immediately.
        sourceFlow.emit(2)
        testScheduler.runCurrent()
        assertEquals(listOf(1, 2), emitted, "Test Step 3: New element after silent period should emit immediately")

        // 4. Subsequent elements should resume normal sampling.
        //    The new sampling window of 100ms starts from the emission of element 2.
        //    Emit element 3 partway into this new window (e.g., at 150ms + 50ms = 200ms total time).
        testScheduler.advanceTimeBy(periodMillis / 2) // Advance to 150ms + 50ms = 200ms
        sourceFlow.emit(3)
        testScheduler.runCurrent()
        // Element 3 should be sampled, not emitted yet.
        assertEquals(listOf(1, 2), emitted, "Test Step 4a: Element 3 (emitted at 200ms) should be sampled")

        // Advance time to complete the sampling window for element 2 (which started at 150ms).
        // Window ends at 150ms + 100ms = 250ms.
        testScheduler.advanceTimeBy(periodMillis / 2) // Advance to 200ms + 50ms = 250ms
        testScheduler.runCurrent()
        // Element 3 should now be emitted.
        assertEquals(listOf(1, 2, 3), emitted, "Test Step 4b: Element 3 should be emitted after its sampling window (at 250ms)")

        // 5. Verify further sampling behavior
        //    Emit element 4. New window for 3 started at 250ms.
        //    Element 4 will be emitted at 250ms + 10ms (arbitrary small delay for emission)
        testScheduler.advanceTimeBy(10L) // Advance to 260ms
        sourceFlow.emit(4)
        testScheduler.runCurrent()
        assertEquals(listOf(1, 2, 3), emitted, "Test Step 5a: Element 4 (emitted at 260ms) should be sampled")

        // Advance time to complete the sampling window for element 3 (which started at 250ms).
        // Window ends at 250ms + 100ms = 350ms.
        testScheduler.advanceTimeBy(periodMillis - 10L) // Advance to 260ms + 90ms = 350ms
        testScheduler.runCurrent()
        assertEquals(listOf(1, 2, 3, 4), emitted, "Test Step 5b: Element 4 should be emitted after its sampling window (at 350ms)")

        job.cancel()
    }
} 