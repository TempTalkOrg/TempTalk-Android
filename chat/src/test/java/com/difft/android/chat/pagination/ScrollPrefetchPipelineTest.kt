package com.difft.android.chat.pagination

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.difft.android.test.TestDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test











@OptIn(ExperimentalCoroutinesApi::class)
class ScrollPrefetchPipelineTest {

    



    private val mainDispatcher = StandardTestDispatcher()

    





    @get:Rule
    val dispatcherRule = TestDispatcherRule(mainDispatcher)

    private val signals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    
    
    @Test
    fun `a burst of scroll signals within one period collapses to at most two checks`() = runTest(mainDispatcher) {
        var checks = 0
        val owner = startedOwner()
        val job = launchScrollPrefetch(signals, owner.lifecycle) { checks++ }
        runCurrent()

        repeat(BURST) { signals.emit(Unit) }
        advanceTimeBy(SCROLL_PREFETCH_THROTTLE_MS + 1)
        runCurrent()

        assertTrue("expected at most 2 checks for $BURST signals, was $checks", checks <= 2)
        assertTrue("the leading-edge check must not be swallowed", checks >= 1)
        job.cancelAndJoin()
    }

    
    @Test
    fun `the first scroll signal is checked without waiting out a period`() = runTest(mainDispatcher) {
        var checks = 0
        val owner = startedOwner()
        val job = launchScrollPrefetch(signals, owner.lifecycle) { checks++ }
        runCurrent()

        signals.emit(Unit)
        advanceTimeBy(SCROLL_PREFETCH_THROTTLE_MS / 2)
        runCurrent()

        assertEquals(1, checks)
        job.cancelAndJoin()
    }

    
    
    @Test
    fun `no check happens while the lifecycle is below STARTED`() = runTest(mainDispatcher) {
        var checks = 0
        val owner = startedOwner()
        val job = launchScrollPrefetch(signals, owner.lifecycle) { checks++ }
        runCurrent()
        owner.registry.currentState = Lifecycle.State.CREATED
        runCurrent()

        repeat(BURST) { signals.emit(Unit) }
        advanceTimeBy(4 * SCROLL_PREFETCH_THROTTLE_MS)
        runCurrent()

        assertEquals(0, checks)
        job.cancelAndJoin()
    }

    
    @Test
    fun `checks resume once the lifecycle is STARTED again`() = runTest(mainDispatcher) {
        var checks = 0
        val owner = startedOwner()
        val job = launchScrollPrefetch(signals, owner.lifecycle) { checks++ }
        runCurrent()
        owner.registry.currentState = Lifecycle.State.CREATED
        runCurrent()
        owner.registry.currentState = Lifecycle.State.STARTED
        runCurrent()

        signals.emit(Unit)
        advanceTimeBy(SCROLL_PREFETCH_THROTTLE_MS + 1)
        runCurrent()

        assertTrue("expected at least one check after returning to STARTED", checks >= 1)
        job.cancelAndJoin()
    }

    
    
    
    @Test
    fun `a throwing check does not kill the collector`() = runTest(mainDispatcher) {
        var checks = 0
        val owner = startedOwner()
        val job = launchScrollPrefetch(signals, owner.lifecycle) {
            checks++
            if (checks == 1) error("transient failure")
        }
        runCurrent()

        signals.emit(Unit)
        advanceTimeBy(SCROLL_PREFETCH_THROTTLE_MS + 1)
        runCurrent()
        assertEquals(1, checks)
        assertTrue("the collector must survive a throwing check", job.isActive)

        signals.emit(Unit)
        advanceTimeBy(SCROLL_PREFETCH_THROTTLE_MS + 1)
        runCurrent()

        assertEquals("the next signal must still be processed", 2, checks)
        assertTrue(job.isActive)

        job.cancelAndJoin()
        assertTrue("cancellation must still stop the collector", job.isCancelled)
    }

    
    private fun startedOwner(): TestOwner = TestOwner().apply {
        registry.currentState = Lifecycle.State.STARTED
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private companion object {
        const val BURST = 30
    }
}
