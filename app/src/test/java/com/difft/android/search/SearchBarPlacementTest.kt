package com.difft.android.search

import com.difft.android.dualpane.SearchReveal
import org.junit.Assert.assertEquals
import org.junit.Test







class SearchBarPlacementTest {

    @Test
    fun `the bar sits 10dp above the keyboard`() {
        assertEquals(20, SearchBarPlacement.bottomMarginPx(keyboardUp = true, navBottomPx = 96, density = 2f))
    }

    @Test
    fun `without the keyboard the bar rests 28dp from the screen bottom`() {
        
        assertEquals(24, SearchBarPlacement.bottomMarginPx(keyboardUp = false, navBottomPx = 32, density = 2f))
        
        assertEquals(56, SearchBarPlacement.bottomMarginPx(keyboardUp = false, navBottomPx = 0, density = 2f))
    }

    @Test
    fun `a tall navigation bar keeps an 8dp gap`() {
        assertEquals(16, SearchBarPlacement.bottomMarginPx(keyboardUp = false, navBottomPx = 96, density = 2f))
    }

    @Test
    fun `results stop 12dp above the bar`() {
        assertEquals(100 + 24, SearchBarPlacement.listBottomPaddingPx(listBottom = 1000, barTop = 900, density = 2f))
    }

    @Test
    fun `the reveal grows from the button radius to the farthest corner`() {
        val full = SearchReveal.coveringRadius(cx = 300f, cy = 400f, width = 400, height = 1000)

        assertEquals(Math.hypot(300.0, 600.0).toFloat(), full, 0.01f)
        assertEquals(31f, SearchReveal.radiusAt(0f, buttonRadius = 31f, full = full), 0.01f)
        assertEquals(full, SearchReveal.radiusAt(1f, buttonRadius = 31f, full = full), 0.01f)
    }

    @Test
    fun `closing fades only after 78 percent`() {
        assertEquals(1f, SearchReveal.closingAlpha(0.5f), 0.001f)
        assertEquals(1f, SearchReveal.closingAlpha(0.78f), 0.001f)
        assertEquals(0.5f, SearchReveal.closingAlpha(0.89f), 0.001f)
        assertEquals(0f, SearchReveal.closingAlpha(1f), 0.001f)
    }
}
