package com.difft.android.base.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassRegionMathTest {
    private val source = GlassSharpRegion(0, 0, 400, 1000)

    @Test
    fun `top and bottom sampling regions leave the unused middle uncaptured`() {
        val regions = GlassRegionMath.merge(listOf(GlassSharpRegion(0, 0, 400, 80),
            GlassSharpRegion(0, 920, 400, 1000)), source, GlassRegionMath.blurMargin(1f))
        assertEquals(listOf(GlassSharpRegion(0, 0, 400, 104), GlassSharpRegion(0, 896, 400, 1000)), regions)
        assertEquals(24, GlassRegionMath.blurMargin(1f))
        assertEquals(72, GlassRegionMath.blurMargin(3f))
        assertTrue(regions.sumOf { it.width * it.height } < source.width * source.height)
    }

    @Test
    fun `sampling overlap and transitive bridges merge regardless of consumer order`() {
        val top = GlassSharpRegion(0, 0, 100, 100)
        val bottom = GlassSharpRegion(0, 300, 100, 400)
        val bridge = GlassSharpRegion(0, 150, 100, 250)
        val expected = listOf(GlassSharpRegion(0, 0, 152, 456))
        assertEquals(expected, GlassRegionMath.merge(listOf(top, bottom, bridge), source, 50, alignment = 8))
        assertEquals(expected, GlassRegionMath.merge(listOf(bridge, bottom, top), source, 50, alignment = 8))
    }

    @Test
    fun `pixel grid rounds outward and available outsets bound the capture`() {
        val extent = GlassSharpRegion(-12, -24, 400, 1024)
        assertEquals(listOf(GlassSharpRegion(-12, -24, 24, 48)),
            GlassRegionMath.merge(listOf(GlassSharpRegion(-9, -21, 17, 41)), extent, 0, alignment = 8))
        assertTrue(GlassRegionMath.merge(emptyList(), extent, 120).isEmpty())
        assertTrue(GlassRegionMath.merge(listOf(GlassSharpRegion(900, 900, 950, 950)), extent, 0).isEmpty())
        assertTrue("a consumer outside the source cannot request a margin-only texture",
            GlassRegionMath.merge(listOf(GlassSharpRegion(410, 100, 420, 120)), extent, 120).isEmpty())
    }

    @Test
    fun `blur coverage rejects overlapping gaps and accepts fractional window coordinates`() {
        val region = GlassSharpRegion(-12, -24, 200, 200)
        assertTrue(GlassRegionMath.covers(region, -11.5f, -23.5f, 199.5f, 199.5f))
        assertTrue(GlassRegionMath.covers(region, -12f, -24f, 200f, 200f))
        assertFalse(GlassRegionMath.covers(region, 100f, 100f, 200f, 250f))
        assertFalse(GlassRegionMath.covers(region, -12.1f, -24f, 200f, 200f))
        assertFalse(GlassRegionMath.covers(region, 0f, 0f, Float.NaN, 50f))
        assertFalse(GlassRegionMath.covers(region, 0f, 0f, 0f, 50f))
    }
}
