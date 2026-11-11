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
        
        testScheduler.advanceTimeBy(40)
        assertEquals(listOf(1), emitted)
        
        testScheduler.advanceTimeBy(40)
        assertEquals(listOf(1), emitted)

        
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
            emit(listOf(1)) 
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
            
        }
        val job = launch {
            testFlow.sampleAfterFirst(100) { it.isNotEmpty() }.collect { emitted.add(it) }
        }
        
        testScheduler.advanceTimeBy(99)
        testScheduler.runCurrent()
        val expectedEmpty: List<List<Int>> = emptyList()
        assertEquals(expectedEmpty, emitted)

        
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

        
        testScheduler.runCurrent()

        shared.emit(1) 
        testScheduler.runCurrent()
        assertEquals(listOf(1), emitted)

        testScheduler.advanceTimeBy(50)
        shared.emit(2) 
        testScheduler.runCurrent()
        assertEquals(listOf(1), emitted)

        testScheduler.advanceTimeBy(50) 
        testScheduler.runCurrent()
        assertEquals(listOf(1, 2), emitted)

        
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
        
        val sourceFlow = kotlinx.coroutines.flow.MutableSharedFlow<Int>()

        val job = launch {
            sourceFlow.sampleAfterFirst(periodMillis).collect { emitted.add(it) }
        }

        
        testScheduler.runCurrent()

        
        sourceFlow.emit(1)
        testScheduler.runCurrent()
        assertEquals(listOf(1), emitted, "Test Step 1: First element should be emitted immediately")

        
        
        
        testScheduler.advanceTimeBy(periodMillis + 50L) 
        testScheduler.runCurrent()
        assertEquals(listOf(1), emitted, "Test Step 2: No new emissions after first period if source was silent")

        
        
        sourceFlow.emit(2)
        testScheduler.runCurrent()
        assertEquals(listOf(1, 2), emitted, "Test Step 3: New element after silent period should emit immediately")

        
        
        
        testScheduler.advanceTimeBy(periodMillis / 2) 
        sourceFlow.emit(3)
        testScheduler.runCurrent()
        
        assertEquals(listOf(1, 2), emitted, "Test Step 4a: Element 3 (emitted at 200ms) should be sampled")

        
        
        testScheduler.advanceTimeBy(periodMillis / 2) 
        testScheduler.runCurrent()
        
        assertEquals(listOf(1, 2, 3), emitted, "Test Step 4b: Element 3 should be emitted after its sampling window (at 250ms)")

        
        
        
        testScheduler.advanceTimeBy(10L) 
        sourceFlow.emit(4)
        testScheduler.runCurrent()
        assertEquals(listOf(1, 2, 3), emitted, "Test Step 5a: Element 4 (emitted at 260ms) should be sampled")

        
        
        testScheduler.advanceTimeBy(periodMillis - 10L) 
        testScheduler.runCurrent()
        assertEquals(listOf(1, 2, 3, 4), emitted, "Test Step 5b: Element 4 should be emitted after its sampling window (at 350ms)")

        job.cancel()
    }
} 