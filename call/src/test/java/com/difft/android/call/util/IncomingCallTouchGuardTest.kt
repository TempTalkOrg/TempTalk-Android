package com.difft.android.call.util

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class IncomingCallTouchGuardTest {

    private var now = 10_000L
    private val guard = IncomingCallTouchGuard(cooldownMs = 500L, clock = { now })

    @Test
    fun `tap inside cooldown is refused`() {
        guard.markShown()
        now += 499
        assertFalse(guard.allowsTap())
        assertEquals(499L, guard.sinceShownMs())
    }

    @Test
    fun `tap at or after cooldown is allowed`() {
        guard.markShown()
        now += 500
        assertTrue(guard.allowsTap())
        now += 5_000
        assertTrue(guard.allowsTap())
    }

    @Test
    fun `markShown restarts the cooldown`() {
        guard.markShown()
        now += 2_000
        assertTrue(guard.allowsTap())
        guard.markShown()
        now += 100
        assertFalse(guard.allowsTap())
    }

    @Test
    fun `records only the pointer-down and reports its provenance`() {
        guard.markShown()
        val down = MotionEvent.obtain(now, now + 1_200, MotionEvent.ACTION_DOWN, 30f, 40f, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val move = MotionEvent.obtain(now, now + 1_300, MotionEvent.ACTION_MOVE, 99f, 99f, 0)
        guard.recordTouch(down)
        guard.recordTouch(move)

        val described = guard.describeLastTouch()
        assertTrue(described, described.contains("touchscreen=true"))
        assertTrue(described, described.contains("x=30 y=40"))
        assertTrue(described, described.contains("sinceShownMs=1200"))
        down.recycle()
        move.recycle()
    }

    @Test
    fun `no touch recorded reads as none`() {
        assertEquals("none", guard.describeLastTouch())
    }
}
