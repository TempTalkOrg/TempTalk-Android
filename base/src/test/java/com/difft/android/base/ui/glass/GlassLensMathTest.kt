package com.difft.android.base.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassLensMathTest {
    @Test
    fun `progress clamps to the unit range and nonfinite input is neutral`() {
        assertEquals(0f, GlassLensMath.progress(-1f), 0f)
        assertEquals(0.5f, GlassLensMath.progress(0.5f), 0f)
        assertEquals(1f, GlassLensMath.progress(2f), 0f)
        assertEquals(0f, GlassLensMath.progress(Float.NaN), 0f)
        assertEquals(0f, GlassLensMath.progress(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun `sample padding covers refraction, dispersion and the blur neighbourhood`() {
        
        assertEquals(98, GlassLensMath.samplePadding(2f, GlassLensStyle.Panel))
        
        assertEquals(58, GlassLensMath.samplePadding(2f, GlassLensStyle.Selection))
    }

    @Test
    fun `demo styles frost only the panel and disperse only the selection`() {
        assertTrue(GlassLensStyle.Panel.vibrancy)
        assertEquals(8f, GlassLensStyle.Panel.blurRadiusDp, 0f)
        assertFalse(GlassLensStyle.Panel.chromatic)
        assertFalse(GlassLensStyle.Selection.vibrancy)
        assertEquals(0f, GlassLensStyle.Selection.blurRadiusDp, 0f)
        assertTrue(GlassLensStyle.Selection.chromatic)
    }
}
