package com.difft.android.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTabPillMathTest {
    @Test
    fun `pointer centers and fractional motion map into logical tab order`() {
        assertEquals(0f, HomeTabPillMath.pointerIndex(30f, 60f, 3, false), 0f)
        assertEquals(1.25f, HomeTabPillMath.pointerIndex(105f, 60f, 3, false), 0f)
        assertEquals(0f, HomeTabPillMath.pointerIndex(150f, 60f, 3, true), 0f)
        assertEquals(2f, HomeTabPillMath.pointerIndex(30f, 60f, 3, true), 0f)
        assertEquals(0f, HomeTabPillMath.pointerIndex(30f, 0f, 3, false), 0f)
    }

    @Test
    fun `drag preserves fractional index and clamps both capsule ends`() {
        assertEquals(1.4f, HomeTabPillMath.dragIndex(1f, 24f, 60f, 3, false), 0.0001f)
        assertEquals(0.6f, HomeTabPillMath.dragIndex(1f, 24f, 60f, 3, true), 0.0001f)
        assertEquals(0f, HomeTabPillMath.dragIndex(1f, -1000f, 60f, 3, false), 0f)
        assertEquals(2f, HomeTabPillMath.dragIndex(1f, 1000f, 60f, 3, false), 0f)
        assertEquals(1f, HomeTabPillMath.dragIndex(1f, 10f, 0f, 3, false), 0f)
    }

    @Test
    fun `release chooses nearest tab without projecting the fling velocity`() {
        assertEquals(0, HomeTabPillMath.nearestIndex(-1f, 3))
        assertEquals(0, HomeTabPillMath.nearestIndex(0.49f, 3))
        assertEquals(1, HomeTabPillMath.nearestIndex(0.5f, 3))
        assertEquals(1, HomeTabPillMath.nearestIndex(1.49f, 3))
        assertEquals(2, HomeTabPillMath.nearestIndex(1.5f, 3))
        assertEquals(2, HomeTabPillMath.nearestIndex(4f, 3))
        assertEquals(80f, HomeTabPillMath.offset(1.25f, 64f, 3), 0f)
    }

    @Test
    fun `stretch is symmetric bounded and returns to the original shape at rest`() {
        assertEquals(PillScale(1f, 1f), HomeTabPillMath.scale(0f, 0f))
        assertEquals(PillScale(1.035f, 1.035f), HomeTabPillMath.scale(0f, 1f))
        val moving = HomeTabPillMath.scale(4f, 0f)
        assertEquals(1.09f, moving.axis, 0.0001f)
        assertEquals(0.97f, moving.cross, 0.0001f)
        val maximum = HomeTabPillMath.scale(1000f, 1f)
        assertEquals(1.2213f, maximum.axis, 0.0001f)
        assertEquals(0.9729f, maximum.cross, 0.0001f)
        assertEquals(maximum, HomeTabPillMath.scale(-1000f, 1f))
        assertEquals(maximum, HomeTabPillMath.scale(Float.POSITIVE_INFINITY, 2f))
        assertEquals(PillScale(1f, 1f), HomeTabPillMath.scale(Float.NaN, 0f))
    }
}
