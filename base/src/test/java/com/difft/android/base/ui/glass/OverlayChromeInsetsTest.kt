package com.difft.android.base.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayChromeInsetsTest {
    @Test
    fun `chrome in another parent uses the scrolling viewport window origin`() {
        assertEquals(OverlayChromeInsets(100, 90), overlayChromeInsets(24, 776, 124, 710))
        assertEquals(OverlayChromeInsets(124, 90), overlayChromeInsets(0, 800, 124, 710))
    }

    @Test
    fun `collapsed chrome and consumed bars do not reserve negative or duplicate space`() {
        assertEquals(OverlayChromeInsets(24, 32), overlayChromeInsets(24, 776, 0, 810, 24, 32))
        assertEquals(OverlayChromeInsets(100, 90), overlayChromeInsets(24, 776, 124, 710, 24, 32))
        assertEquals(OverlayChromeInsets(0, 0), overlayChromeInsets(24, 776, null, null))
    }
}
