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

/**
 * Cases #83 and #88 — the throttling and lifecycle halves of the scroll-prefetch pipeline.
 *
 * `onScrolled` fires on every frame; without the sampling this would run a page-load check ~60
 * times a second. The leading-edge emission is equally load-bearing: `debounce` would never fire
 * during a continuous fling and plain `sample` would miss short ones.
 *
 * The `onScrolled` -> signal half needs a laid-out RecyclerView inside the real Fragment, so it is
 * covered by the on-device matrix instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrollPrefetchPipelineTest {

    /**
     * `flowWithLifecycle` observes the lifecycle on `Dispatchers.Main`, so the pipeline needs one.
     * Sharing this dispatcher's scheduler with `runTest` keeps the sampling delays on virtual time.
     */
    private val mainDispatcher = StandardTestDispatcher()

    /**
     * NOTE — `advanceUntilIdle()` must not be used in this class. The sampling operator keeps a
     * periodic ticker alive for as long as the collector lives, so "until idle" never arrives:
     * these cases step virtual time explicitly instead.
     */

    @get:Rule
    val dispatcherRule = TestDispatcherRule(mainDispatcher)

    private val signals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // #83 — a burst inside one sampling window collapses to the leading edge plus, at most, one
    // period-end check.
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

    // …and the leading edge really is immediate: a check happens before the first period elapses.
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

    // #88 — below STARTED the collector is torn down, so a scroll that keeps arriving while the
    // conversation is in the background cannot page (nor keep a sampling ticker alive).
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

    // …and it resumes when the conversation comes back to the foreground.
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

    // A check that throws must not kill the collector: this Job lives for the whole view lifecycle,
    // so one transient DB failure escaping would silently disable prefetch until the view is
    // recreated. Cancellation must still tear the collector down.
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

    /** `createUnsafe` skips the main-thread assertion; there is no Looper in this plain unit test. */
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
