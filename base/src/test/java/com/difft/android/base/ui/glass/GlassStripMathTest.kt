package com.difft.android.base.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassStripMathTest {
    @Test
    fun `live strip fades across the whole inset plus tail`() {
        assertEquals(1f, GlassStripMath.ramp(0f, 100f), 0f)
        assertEquals(0.8f, GlassStripMath.ramp(20f, 100f), 0.0001f)
        assertEquals(0.5f, GlassStripMath.ramp(50f, 100f), 0f)
        assertEquals(0f, GlassStripMath.ramp(100f, 100f), 0f)
        assertEquals(1f, GlassStripMath.ramp(-10f, 100f), 0f)
        assertEquals(0f, GlassStripMath.ramp(120f, 100f), 0f)
        assertEquals(0f, GlassStripMath.ramp(0f, 0f), 0f)
    }

    @Test
    fun `fallback plateau boundary is the fraction of the extent occupied by the inset`() {
        assertEquals(0.2f, GlassStripMath.insetFraction(20f, 100f), 0f)
        assertEquals(0f, GlassStripMath.insetFraction(0f, 100f), 0f)
        assertEquals(0.5f, GlassStripMath.insetFraction(50f, 100f), 0f)
        assertEquals(1f, GlassStripMath.insetFraction(120f, 100f), 0f)
        assertEquals(0f, GlassStripMath.insetFraction(20f, 0f), 0f)
    }
}
