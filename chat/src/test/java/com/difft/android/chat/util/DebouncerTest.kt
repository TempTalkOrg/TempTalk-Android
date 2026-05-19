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
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DebouncerTest {

    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Test
    fun `publish delays runnable by interval`() {
        val debouncer = Debouncer(500)
        val runnable = mockk<Runnable>(relaxed = true)

        debouncer.publish(runnable)

        // Not yet fired.
        mainLooper.idleFor(499, TimeUnit.MILLISECONDS)
        verify(exactly = 0) { runnable.run() }

        // After threshold, fires once.
        mainLooper.idleFor(2, TimeUnit.MILLISECONDS)
        verify(exactly = 1) { runnable.run() }
    }

    @Test
    fun `subsequent publish resets the timer and only last runs`() {
        val debouncer = Debouncer(500)
        val first = mockk<Runnable>(relaxed = true)
        val second = mockk<Runnable>(relaxed = true)

        debouncer.publish(first)
        mainLooper.idleFor(400, TimeUnit.MILLISECONDS)
        // Before first fires, republish — should cancel first and schedule second.
        debouncer.publish(second)

        mainLooper.idleFor(499, TimeUnit.MILLISECONDS)
        verify(exactly = 0) { first.run() }
        verify(exactly = 0) { second.run() }

        mainLooper.idleFor(2, TimeUnit.MILLISECONDS)
        verify(exactly = 0) { first.run() }
        verify(exactly = 1) { second.run() }
    }

    @Test
    fun `clear cancels a pending runnable`() {
        val debouncer = Debouncer(500)
        val runnable = mockk<Runnable>(relaxed = true)

        debouncer.publish(runnable)
        mainLooper.idleFor(100, TimeUnit.MILLISECONDS)
        debouncer.clear()

        mainLooper.idleFor(1000, TimeUnit.MILLISECONDS)
        verify(exactly = 0) { runnable.run() }
    }

    @Test
    fun `time unit convenience constructor converts to milliseconds`() {
        val debouncer = Debouncer(1, TimeUnit.SECONDS)
        val runnable = mockk<Runnable>(relaxed = true)

        debouncer.publish(runnable)
        mainLooper.idleFor(999, TimeUnit.MILLISECONDS)
        verify(exactly = 0) { runnable.run() }

        mainLooper.idleFor(2, TimeUnit.MILLISECONDS)
        verify(exactly = 1) { runnable.run() }
    }

    @Test
    fun `MockK can mock Debouncer (open class)`() {
        // Regression guard for jobmanager tests: if Debouncer becomes final,
        // mockk<Debouncer> throws MockKException at runtime.
        val mock: Debouncer = mockk(relaxed = true)
        mock.publish { /* no-op */ }
        verify { mock.publish(any()) }
    }

    @Test
    fun `multiple publishes collapse to single execution`() {
        val debouncer = Debouncer(200)
        val runnable = mockk<Runnable>(relaxed = true)

        repeat(10) {
            debouncer.publish(runnable)
            mainLooper.idleFor(50, TimeUnit.MILLISECONDS)
        }

        // After all publishes, only ~500ms of idle has passed but each publish reset the timer.
        // Let the timer finally expire.
        mainLooper.idleFor(201, TimeUnit.MILLISECONDS)
        verify(exactly = 1) { runnable.run() }
    }

    @Test
    fun `open publish and clear are overridable`() {
        // Sanity: confirm the declared methods are callable on a subclass,
        // which proves they're open.
        var publishCount = 0
        var clearCount = 0
        val sub = object : Debouncer(100) {
            override fun publish(runnable: Runnable) {
                publishCount++
                super.publish(runnable)
            }
            override fun clear() {
                clearCount++
                super.clear()
            }
        }
        sub.publish(mockk(relaxed = true))
        sub.clear()
        assertEquals(1, publishCount)
        assertEquals(1, clearCount)
    }
}
