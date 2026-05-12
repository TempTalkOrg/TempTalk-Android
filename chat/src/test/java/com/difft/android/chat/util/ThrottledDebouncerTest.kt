package com.difft.android.chat.util

import android.os.Looper
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/**
 * Tests for [ThrottledDebouncer].
 *
 * NOTE: `ThrottledDebouncer` computes its own delay via `System.currentTimeMillis()`, which is
 * NOT advanced by Robolectric's `Looper.idleFor(...)` (that only advances the Handler uptime
 * clock). That makes fine-grained throttle-window assertions flaky in unit tests. We therefore
 * cover the deterministic behaviors (first-publish scheduling, runnable replacement within a
 * single dispatch cycle, clear cancellation, open-class mockability) and rely on downstream
 * integration / manual QA for wall-clock-throttled sequencing.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ThrottledDebouncerTest {

    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Test
    fun `first publish is scheduled with zero delay`() {
        val td = ThrottledDebouncer(500)
        val runnable = mockk<Runnable>(relaxed = true)

        td.publish(runnable)

        // Message sits in the looper queue until we drain.
        verify(exactly = 0) { runnable.run() }

        // lastRun is 0 on first publish → computed delay collapses to 0 → fires on idle.
        mainLooper.idle()
        verify(exactly = 1) { runnable.run() }
    }

    @Test
    fun `publishes within active window replace the pending runnable`() {
        val td = ThrottledDebouncer(500)
        val first = mockk<Runnable>(relaxed = true)
        val second = mockk<Runnable>(relaxed = true)

        td.publish(first)
        // Pending message still queued — publish again before looper drains.
        td.publish(second)

        mainLooper.idle()
        verify(exactly = 0) { first.run() }
        verify(exactly = 1) { second.run() }
    }

    @Test
    fun `repeated publishes before any drain collapse to a single execution`() {
        val td = ThrottledDebouncer(500)
        val runnables = List(5) { mockk<Runnable>(relaxed = true) }

        for (r in runnables) td.publish(r)
        mainLooper.idle()

        // Only the last one runs; earlier ones are overwritten before dispatch.
        for (i in 0 until runnables.size - 1) {
            verify(exactly = 0) { runnables[i].run() }
        }
        verify(exactly = 1) { runnables.last().run() }
    }

    @Test
    fun `clear cancels a pending message before it fires`() {
        val td = ThrottledDebouncer(500)
        val runnable = mockk<Runnable>(relaxed = true)

        td.publish(runnable)
        td.clear()

        // Drain — if the message had survived clear(), it would fire now.
        mainLooper.idle()
        mainLooper.idleFor(1000, TimeUnit.MILLISECONDS)
        verify(exactly = 0) { runnable.run() }
    }

    @Test
    fun `MockK can mock ThrottledDebouncer (open class)`() {
        val mock: ThrottledDebouncer = mockk(relaxed = true)
        mock.publish { /* no-op */ }
        verify { mock.publish(any()) }
    }
}
