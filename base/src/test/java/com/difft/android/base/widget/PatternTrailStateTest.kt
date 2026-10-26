package com.difft.android.base.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class PatternTrailStateTest {

    private fun state() = PatternTrailState(fadeDelayMs = 150, fadeMs = 500, pulseMs = 250)

    @Test
    fun `each segment holds for the delay, then is wiped towards its end while fading`() {
        val trail = state()
        trail.onDotAdded(0)
        trail.onDotAdded(1_000) // segment 0 drawn at 1000

        assertEquals(1f, trail.segmentAlpha(0, 1_100), 0f)
        assertEquals(0f, trail.wipeProgress(0, 1_100), 0f)
        assertEquals(0.5f, trail.wipeProgress(0, 1_400), 0.001f)
        assertEquals(0.5f, trail.segmentAlpha(0, 1_400), 0.001f)
        assertEquals(0f, trail.segmentAlpha(0, 1_650), 0f)
    }

    @Test
    fun `fast drawing keeps several segments visible, each on its own clock`() {
        val trail = state()
        trail.onDotAdded(0)
        trail.onDotAdded(100) // segment 0
        trail.onDotAdded(200) // segment 1
        trail.onDotAdded(300) // segment 2

        assertEquals(1f, trail.segmentAlpha(0, 250), 0f)
        assertEquals(1f, trail.segmentAlpha(1, 250), 0f)
        assertEquals(0.5f, trail.segmentAlpha(0, 500), 0.001f)
        assertEquals(0.7f, trail.segmentAlpha(1, 500), 0.001f)
        assertEquals(0.9f, trail.segmentAlpha(2, 500), 0.001f)
    }

    @Test
    fun `slow drawing lets the last segment fade before the next dot`() {
        val trail = state()
        trail.onDotAdded(0)
        trail.onDotAdded(100)

        assertEquals(0f, trail.segmentAlpha(0, 2_000), 0f)
        assertEquals(false, trail.isAnimating(2_000, pathHidden = false))
    }

    @Test
    fun `hidden path flashes every dot on its own clock`() {
        val trail = state()
        trail.onDotAdded(0)
        trail.onDotAdded(100)

        assertEquals(0.6f, trail.pulseAlpha(0, 100), 0.001f)
        assertEquals(1f, trail.pulseAlpha(1, 100), 0f)
        assertEquals(0.5f, trail.pulseAlpha(1, 225), 0.001f)
        assertEquals(0f, trail.pulseAlpha(1, 350), 0f)

        // Reaching another dot does not cut the previous flash short.
        trail.onDotAdded(200)
        assertEquals(0.6f, trail.pulseAlpha(1, 200), 0.001f)
        assertEquals(1f, trail.pulseAlpha(2, 200), 0f)
    }

    @Test
    fun `animation is reported only while something is still visible`() {
        val trail = state()
        assertEquals(false, trail.isAnimating(0, pathHidden = false))
        trail.onDotAdded(0)
        trail.onDotAdded(0)

        assertEquals(true, trail.isAnimating(600, pathHidden = false))
        assertEquals(false, trail.isAnimating(650, pathHidden = false))
        assertEquals(true, trail.isAnimating(100, pathHidden = true))
        assertEquals(false, trail.isAnimating(250, pathHidden = true))

        trail.reset()
        assertEquals(false, trail.isAnimating(0, pathHidden = true))
    }
}
