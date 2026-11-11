package com.difft.android.base.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassSharpMathTest {
    @Test
    fun `subpixel sampling bounds round outward without allocating a page-sized crop`() {
        val region = GlassSharpMath.region(96.6f, 197.8f, 143.1f, 230.3f, 0, 0, 1080, 2400)!!
        assertEquals(GlassSharpRegion(96, 197, 144, 231), region)
        assertEquals(48, region.width)
        assertEquals(34, region.height)
    }

    @Test
    fun `source intersection retains available system-bar outsets`() {
        assertEquals(GlassSharpRegion(-8, -24, 32, 40),
            GlassSharpMath.region(-20.5f, -40f, 31.2f, 39.1f, -8, -24, 400, 800))
        assertEquals(GlassSharpRegion(380, 790, 400, 800),
            GlassSharpMath.region(380f, 790f, 420f, 830f, -8, -24, 400, 800))
    }

    @Test
    fun `empty offscreen and invalid regions cannot create native recordings`() {
        assertNull(GlassSharpMath.region(1f, 1f, 1f, 2f, 0, 0, 100, 100))
        assertNull(GlassSharpMath.region(101f, 1f, 110f, 2f, 0, 0, 100, 100))
        assertNull(GlassSharpMath.region(Float.NaN, 1f, 10f, 2f, 0, 0, 100, 100))
        assertNull(GlassSharpMath.region(1f, 1f, Float.POSITIVE_INFINITY, 2f, 0, 0, 100, 100))
        assertNull(GlassSharpMath.region(1f, 1f, 10f, 2f, 0, 0, 0, 100))
    }
}
