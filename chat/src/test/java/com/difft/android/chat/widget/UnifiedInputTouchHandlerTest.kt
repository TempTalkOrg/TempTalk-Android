package com.difft.android.chat.widget

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class UnifiedInputTouchHandlerTest {

    private class Recorder(var longPressResult: Boolean = true) : UnifiedInputTouchHandler.Callbacks {
        val events = mutableListOf<String>()
        override fun onPressStateChanged(pressed: Boolean) { events += "pressed=$pressed" }
        override fun onTap() { events += "tap" }
        override fun onLongPress(): Boolean { events += "long"; return longPressResult }
        override fun onHoldMove(rawX: Float, rawY: Float) { events += "move" }
        override fun onHoldEnd() { events += "end" }
    }

    private val view = View(RuntimeEnvironment.getApplication())
    private val callbacks = Recorder()
    private var intercept = true
    private val handler = UnifiedInputTouchHandler(
        touchSlop = SLOP,
        shouldIntercept = { intercept },
        callbacks = callbacks,
    )

    @Test
    fun `a release before the threshold is a tap`() {
        assertTrue(touch(MotionEvent.ACTION_DOWN))
        idle(UnifiedInputTouchHandler.LONG_PRESS_TIMEOUT_MS - 1)
        touch(MotionEvent.ACTION_UP)
        idle(UnifiedInputTouchHandler.LONG_PRESS_TIMEOUT_MS)

        assertEquals(listOf("pressed=true", "pressed=false", "tap"), callbacks.events)
    }

    @Test
    fun `holding to the threshold starts a hold that receives moves and the release`() {
        touch(MotionEvent.ACTION_DOWN)
        idle(UnifiedInputTouchHandler.LONG_PRESS_TIMEOUT_MS)
        touch(MotionEvent.ACTION_MOVE, x = 200f)
        touch(MotionEvent.ACTION_UP, x = 200f)

        assertEquals(listOf("pressed=true", "pressed=false", "long", "move", "end"), callbacks.events)
    }

    @Test
    fun `moving past the slop before the threshold abandons the gesture`() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_MOVE, x = 10f + SLOP + 1)
        idle(UnifiedInputTouchHandler.LONG_PRESS_TIMEOUT_MS)
        touch(MotionEvent.ACTION_UP, x = 10f + SLOP + 1)

        assertEquals(listOf("pressed=true", "pressed=false"), callbacks.events)
    }

    @Test
    fun `text-input mode leaves the whole gesture to the field`() {
        intercept = false

        assertFalse(touch(MotionEvent.ACTION_DOWN))
        idle(UnifiedInputTouchHandler.LONG_PRESS_TIMEOUT_MS)
        assertFalse(touch(MotionEvent.ACTION_UP))

        assertTrue(callbacks.events.isEmpty())
    }

    @Test
    fun `a cancelled stream ends an active hold`() {
        touch(MotionEvent.ACTION_DOWN)
        idle(UnifiedInputTouchHandler.LONG_PRESS_TIMEOUT_MS)
        touch(MotionEvent.ACTION_CANCEL)

        assertEquals("end", callbacks.events.last())
    }

    @Test
    fun `a hold that did not start ignores the rest of the gesture`() {
        callbacks.longPressResult = false

        touch(MotionEvent.ACTION_DOWN)
        idle(UnifiedInputTouchHandler.LONG_PRESS_TIMEOUT_MS)
        touch(MotionEvent.ACTION_MOVE, x = 200f)
        touch(MotionEvent.ACTION_UP, x = 200f)

        assertEquals(listOf("pressed=true", "pressed=false", "long"), callbacks.events)
    }

    private fun touch(action: Int, x: Float = 10f): Boolean {
        val event = MotionEvent.obtain(0L, 0L, action, x, 10f, 0)
        return handler.onTouch(view, event).also { event.recycle() }
    }

    private fun idle(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val SLOP = 8
    }
}
