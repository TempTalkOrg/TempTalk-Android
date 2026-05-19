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

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ThrottlerTest {

    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Test
    fun `first publish runs immediately`() {
        val throttler = Throttler(500)
        val runnable = mockk<Runnable>(relaxed = true)

        throttler.publish(runnable)

        // Leading edge: should have run synchronously during publish(), no looper idle needed.
        verify(exactly = 1) { runnable.run() }
    }

    @Test
    fun `subsequent publishes within threshold are dropped`() {
        val throttler = Throttler(500)
        val first = mockk<Runnable>(relaxed = true)
        val second = mockk<Runnable>(relaxed = true)
        val third = mockk<Runnable>(relaxed = true)

        throttler.publish(first)
        throttler.publish(second)
        throttler.publish(third)

        verify(exactly = 1) { first.run() }
        verify(exactly = 0) { second.run() }
        verify(exactly = 0) { third.run() }
    }

    @Test
    fun `after threshold expires new publish runs again`() {
        val throttler = Throttler(300)
        val first = mockk<Runnable>(relaxed = true)
        val second = mockk<Runnable>(relaxed = true)

        throttler.publish(first)
        verify(exactly = 1) { first.run() }

        // Advance past the throttle window.
        mainLooper.idleFor(301, TimeUnit.MILLISECONDS)

        throttler.publish(second)
        verify(exactly = 1) { second.run() }
    }

    @Test
    fun `clear allows immediate re-publish`() {
        val throttler = Throttler(1000)
        val first = mockk<Runnable>(relaxed = true)
        val second = mockk<Runnable>(relaxed = true)

        throttler.publish(first)
        throttler.publish(second) // dropped
        verify(exactly = 0) { second.run() }

        throttler.clear()
        throttler.publish(second)
        verify(exactly = 1) { second.run() }
    }

    @Test
    fun `MockK can mock Throttler (open class)`() {
        val mock: Throttler = mockk(relaxed = true)
        mock.publish { /* no-op */ }
        verify { mock.publish(any()) }
    }
}
