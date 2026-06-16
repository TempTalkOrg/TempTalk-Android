package com.difft.android.base

/*
 * Copyright 2016-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

import com.difft.android.base.utils.ChunkingMethod
import com.difft.android.base.utils.chunked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTimedValue

@ExperimentalTime
@OptIn(ExperimentalCoroutinesApi::class)
class ChunkedTest {

    private var actionIndex = AtomicInteger()
    private var finished = AtomicBoolean()

    @Test
    fun testEmptyFlowSizeBasedChunking() = runTest {
        val emptyFlow = emptyFlow<Int>()
        val result = emptyFlow.chunked(ChunkingMethod.BySize(5)).toList()
        assertTrue(result.isEmpty())
    }

    @Test
    fun testUndersizedFlowSizeBasedChunking() = runTest {
        val undersizedFlow = flow<Int> {
            for (i in 1..3) emit(i)
        }
        val result = undersizedFlow.chunked(ChunkingMethod.BySize(5)).toList()
        assertEquals(1, result.size)
        assertEquals(listOf(1, 2, 3), result.first())
    }

    @Test
    fun testOversizedFlowSizeBasedChunking() = runTest {
        val oversizedFlow = flow<Int> {
            for (i in 1..10) emit(i)
        }
        val result = oversizedFlow.chunked(ChunkingMethod.BySize(3)).toList()
        assertEquals(4, result.size)
        assertEquals(3, result.first().size)
        assertEquals(1, result[3].size)

    }

    @Test
    fun testEmptyFlowNaturalChunking() = runTest {
        val emptyFlow = emptyFlow<Int>()
        val result = emptyFlow.chunked(ChunkingMethod.Natural()).toList()
        assertTrue(result.isEmpty())
    }

    @Test
    fun testFastCollectorNaturalChunking() = withVirtualTime {
        val slowProducer = flow<Int> {
            for (i in 1..10) {
                delay(5)
                emit(i)
            }
        }

        val result = slowProducer.chunked(ChunkingMethod.Natural()).toList()
        assertEquals(10, result.size)
        result.forEach { assertEquals(1, it.size) }

        finish(1)
    }

    @Test
    fun testSlowCollectorNaturalChunking() = withVirtualTime {
        val producerInterval = 5L
        val fastProducer = flow<Int> {
            emit(1)
            expect(1)
            delay(producerInterval)

            emit(2)
            expect(3)
            delay(producerInterval)

            emit(3)
            expect(4)
            delay(producerInterval)

            emit(4)
            expect(6)
            delay(producerInterval)

            emit(5)
            expect(7)
            delay(producerInterval)
        }

        val result = fastProducer.chunked(ChunkingMethod.Natural()).withIndex().onEach { indexed ->
            when (indexed.index) {
                0 -> expect(2)
                1 -> expect(5)
                2 -> finish(8)
            }
            delay(11)
        }.toList()

        assertEquals(3, result.size)
        assertEquals(1, result.first().value.size)
        for (i in 1..2) assertEquals(2, result[i].value.size)
    }

    @Test
    fun testErrorPropagationInNaturalChunking() = runTest {
        val exception = IllegalArgumentException()
        val failedFlow = flow<Int> {
            emit(1)
            emit(2)
            throw exception
        }
        var catchedException: Throwable? = null

        val result = failedFlow
            .chunked(ChunkingMethod.Natural())
            .catch { e ->
                catchedException = e
                emit(listOf(3))
            }
            .toList()

        assertTrue(catchedException is IllegalArgumentException)
        assertEquals(3, result.first().single())
    }

    @Test
    fun testEmptyFlowWithSlowTimeBasedChunking() = runTest {
        val emptyFlow = emptyFlow<Int>()
        val result = measureTimedValue { emptyFlow.chunked(ChunkingMethod.ByTime(intervalMs = 10 * 1000)).toList() }
        assertTrue(result.value.isEmpty())
        assertTrue(result.duration < 500.milliseconds)
    }

    @Test
    fun testErrorPropagationInTimeBasedChunking() = runTest {
        val exception = IllegalArgumentException()
        val failedFlow = flow<Int> {
            emit(1)
            emit(2)
            throw exception
        }
        var catchedException: Throwable? = null

        val result = failedFlow
            .chunked(ChunkingMethod.ByTime(10 * 10_000))
            .catch { e ->
                catchedException = e
                emit(listOf(3))
            }
            .toList()

        assertTrue(catchedException is IllegalArgumentException)
        assertEquals(3, result.first().single())
    }

    @Test
    fun testTimeBasedChunkingOfMultipleElements() = withVirtualTime {
        val producer = flow<Int> {
            for (i in 1..10) {
                delay(1000)
                emit(i)
            }
        }

        val result = producer.chunked(ChunkingMethod.ByTime(5500)).toList()

        finish(1)

        assertEquals(2, result.size)
        assertEquals(5, result.first().size)
        assertEquals(5, result[1].size)
    }
    /**
     * Asserts that this invocation is `index`-th in the execution sequence (counting from one).
     */
    public  fun expect(index: Int) {
        val wasIndex = actionIndex.incrementAndGet()
        check(index == wasIndex) { "Expecting action index $index but it is actually $wasIndex" }
    }
    public fun finish(index: Int) {
        expect(index)
        check(!finished.getAndSet(true)) { "Should call 'finish(...)' at most once" }
    }
    @Test
    fun testTimeBasedChunkingWithMaxChunkSizeSuspendingProducer() = runTest {
        val producer = flow<Int> {
            for (i in 1..10) {
                emit(i)
            }
        }

        val startTime = currentTime
        val result = producer.chunked(ChunkingMethod.ByTime(200, maxSize = 5)).toList()
        val duration = currentTime - startTime

        finish(1)

        assertEquals(2, result.size)
        assertEquals(5, result.first().size)
        assertEquals(5, result[1].size)
        assertTrue(duration >= 200L, "expected time at least 200 ms but was: $duration")
    }

    @Test
    fun testEmptyFlowTimeOrSizeBasedChunking() = runTest {
        val emptyFlow = emptyFlow<Int>()
        val result = measureTimedValue {
            emptyFlow.chunked(ChunkingMethod.ByTimeOrSize(intervalMs = 10 * 1000, maxSize = 5)).toList()
        }
        assertTrue(result.value.isEmpty())
        assertTrue(result.duration < 500.milliseconds)
    }

    @Test
    fun testMultipleElementsFillingBufferWithTimeOrSizeBasedChunking() = runTest {
        val flow = flow<Int> {
            for (i in 1..10) {
                emit(i)
            }
        }
        val result = measureTimedValue {
            flow.chunked(ChunkingMethod.ByTimeOrSize(intervalMs = 10 * 1000, maxSize = 5)).toList()
        }
        assertEquals(2, result.value.size)
        assertEquals(5, result.value.first().size)
        assertEquals(5, result.value[1].size)
        assertTrue(result.duration < 500.milliseconds)
    }

    @Test
    fun testMultipleElementsNotFillingBufferWithTimeOrSizeBasedChunking() = withVirtualTime {
        val flow = flow {
            for (i in 1..5) {
                delay(500)
                emit(i)
            }
        }
        val result = flow.chunked(ChunkingMethod.ByTimeOrSize(intervalMs = 1100, maxSize = 500)).toList()

        assertEquals(3, result.size)
        assertEquals(2, result.first().size)
        assertEquals(2, result[1].size)
        assertEquals(1, result[2].size)

        finish(1)
    }
    /**
     * Runs a test with a virtual time source backed by `runTest`'s built-in
     * `TestScope.testScheduler` — `delay()` and `withTimeout()` advance virtual
     * time without actually sleeping. [UnconfinedTestDispatcher] is used so
     * that:
     *  - **virtual time** still applies (the dispatcher implements `Delay`
     *    against [testScheduler], so `delay()` skips wall-clock); and
     *  - **eager dispatch** is preserved — continuations resume inline on the
     *    calling thread, which the tests rely on for their interleaved
     *    `expect(1)`, `expect(2)`, … ordering between producer and collector.
     *
     * [ensureFinished] enforces that `finish(N)` was called inside the block,
     * catching early-return bugs.
     *
     * (Previously: custom `VirtualTimeDispatcher` using the internal
     * `processNextEventInCurrentThread()` — removed in kotlinx-coroutines
     * 1.11. `UnconfinedTestDispatcher(testScheduler)` provides equivalent
     * semantics through the public test API. Note: switching to
     * `Dispatchers.Unconfined` instead would defeat virtual time —
     * `Unconfined` does not implement `Delay`, so `delay()` would fall
     * through to `DefaultDelay` and consume real wall-clock.)
     */
    public fun withVirtualTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(UnconfinedTestDispatcher(testScheduler)) {
            block()
            ensureFinished()
        }
    }
    public fun ensureFinished() {
        require(finished.get()) { "finish(...) should be caller prior to this check" }
    }
}