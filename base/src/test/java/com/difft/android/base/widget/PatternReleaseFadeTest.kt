package com.difft.android.base.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternReleaseFadeTest {

    @Test
    fun `released snapshot fades out linearly and is dropped when done`() {
        val fade = PatternReleaseFade(durationMs = 500)
        fade.dots.add(floatArrayOf(10f, 10f, 1f))
        fade.start(1_000)

        assertEquals(1f, fade.factor(1_000), 0f)
        assertEquals(0.5f, fade.factor(1_250), 0.001f)
        assertEquals(0f, fade.factor(1_500), 0f)
        assertTrue(fade.dots.isEmpty())
    }

    @Test
    fun `nothing captured means nothing to fade`() {
        val fade = PatternReleaseFade(durationMs = 500)
        fade.start(0)

        assertEquals(0f, fade.factor(0), 0f)
    }

    @Test
    fun `clearing stops the fade at once`() {
        val fade = PatternReleaseFade(durationMs = 500)
        fade.dots.add(floatArrayOf(0f, 0f, 1f))
        fade.start(0)

        fade.clear()

        assertEquals(0f, fade.factor(100), 0f)
    }
}
