package com.difft.android.base.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassMathTest {
    @Test
    fun `live mode requires all capability gates including exact memory boundary`() {
        assertTrue(GlassMath.canRenderLive(31, false, GlassTuning.MIN_MEMORY_BYTES))
        assertTrue(GlassMath.canRenderLive(36, false, 8L * 1024 * 1024 * 1024))
        assertFalse(GlassMath.canRenderLive(30, false, GlassTuning.MIN_MEMORY_BYTES))
        assertFalse(GlassMath.canRenderLive(31, true, GlassTuning.MIN_MEMORY_BYTES))
        assertFalse(GlassMath.canRenderLive(31, false, GlassTuning.MIN_MEMORY_BYTES - 1))
        assertFalse(GlassMath.canRenderLive(31, false, 0))
    }

    @Test
    fun `reduced radius restores the same blur distance at each density`() {
        assertEquals(2f, GlassMath.blurRadiusPx(1f), 0f)
        assertEquals(6f, GlassMath.blurRadiusPx(3f), 0f)
        assertEquals(24f, GlassMath.blurRadiusPx(3f) * GlassTuning.DOWNSAMPLE, 0f)
        assertEquals(3f, GlassMath.blurRadiusPx(3f, downsample = 8f), 0f)
    }

    @Test
    fun `source translation includes pane origin and subtracts consumer window position`() {
        assertEquals(0f, GlassMath.consumerOffset(0f, 0f), 0f)
        assertEquals(-72f, GlassMath.consumerOffset(400f, 472f), 0f)
        assertEquals(24f, GlassMath.consumerOffset(60f, 36f), 0f)
        assertEquals(-12.5f, GlassMath.consumerOffset(20.5f, 33f), 0f)
    }
}
