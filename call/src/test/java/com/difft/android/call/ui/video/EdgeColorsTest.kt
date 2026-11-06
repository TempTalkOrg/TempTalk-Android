package com.difft.android.call.ui.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

/** Edge colours for the ambient letterbox (call-layout simulator `src/sim/letterbox.ts`). */
class EdgeColorsTest {

    @Test
    fun `each side averages its outermost row or column into eight samples, toned`() {
        // 16 × 8: the left half black, the right half white.
        val w = 16
        val h = 8
        val pixels = IntArray(w * h) { i -> if (i % w < w / 2) BLACK else WHITE }
        val colors = edgeColorsOf(pixels, w, h)!!
        val white = toned(WHITE)
        assertArrayEquals(IntArray(EDGE_SAMPLES) { if (it < EDGE_SAMPLES / 2) BLACK else white }, colors.top)
        assertArrayEquals(colors.top, colors.bottom)
        assertArrayEquals(IntArray(EDGE_SAMPLES) { BLACK }, colors.left)
        assertArrayEquals(IntArray(EDGE_SAMPLES) { white }, colors.right)
        assertEquals(2f, colors.aspect)
    }

    @Test
    fun `a side shorter than the samples repeats its pixels`() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()
        val colors = edgeColorsOf(intArrayOf(red, green, blue, red, green, blue), 3, 2)!!
        val expected = intArrayOf(red, red, red, green, green, green, blue, blue).map(::toned).toIntArray()
        assertArrayEquals(expected, colors.top)
    }

    @Test
    fun `an empty image has no colours`() {
        assertNull(edgeColorsOf(IntArray(0), 0, 0))
        assertNull(edgeColorsOf(IntArray(3), 2, 2))
    }

    @Test
    fun `toning saturates by 1_35 and dims to 0_72, as the simulator's filter`() {
        assertEquals("grey only dims", 0xFF5C5C5C.toInt(), toned(0xFF808080.toInt()))
        assertEquals("white dims to 184", 0xFFB8B8B8.toInt(), toned(WHITE))
        assertEquals("red gets redder, the others stay at 0", 0xFFEA0000.toInt(), toned(0xFFFF0000.toInt()))
    }

    @Test
    fun `the next frame is read back about 32 px long, within 0_01 and 0_5 of the frame`() {
        assertEquals(0.025f, nextEdgeScale(0.05f, 64), 1e-6f)
        assertEquals(0.01f, nextEdgeScale(0.01f, 640), 1e-6f)
        assertEquals(0.5f, nextEdgeScale(0.2f, 4), 1e-6f)
    }
}
