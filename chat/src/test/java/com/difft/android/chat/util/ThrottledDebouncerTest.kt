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
class ThrottledDebouncerTest {

    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Test
    fun `first publish is scheduled with zero delay`() {
        val td = ThrottledDebouncer(500)
        val runnable = mockk<Runnable>(relaxed = true)

        td.publish(runnable)

        
        verify(exactly = 0) { runnable.run() }

        
        mainLooper.idle()
        verify(exactly = 1) { runnable.run() }
    }

    @Test
    fun `publishes within active window replace the pending runnable`() {
        val td = ThrottledDebouncer(500)
        val first = mockk<Runnable>(relaxed = true)
        val second = mockk<Runnable>(relaxed = true)

        td.publish(first)
        
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

        
        mainLooper.idle()
        mainLooper.idleFor(1000, TimeUnit.MILLISECONDS)
        verify(exactly = 0) { runnable.run() }
    }

    @Test
    fun `MockK can mock ThrottledDebouncer (open class)`() {
        val mock: ThrottledDebouncer = mockk(relaxed = true)
        mock.publish {  }
        verify { mock.publish(any()) }
    }
}
