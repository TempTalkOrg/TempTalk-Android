package com.difft.android.base.widget

import com.difft.android.base.widget.PatternScreenSpacing.Result
import org.junit.Assert.assertEquals
import org.junit.Test


class PatternScreenSpacingTest {

    private fun compute(available: Int, grid: Int, logoBlock: Int = 160) =
        PatternScreenSpacing.compute(1f, available, header = 28, footer = 36, grid = grid, logoBlock = logoBlock)

    @Test
    fun `design baseline iPhone 812 matches the spec`() {
        
        assertEquals(Result(showLogo = true, top = 85, headerToGrid = 80), compute(734, 244))
    }

    @Test
    fun `logo is hidden when the full group does not fit`() {
        
        assertEquals(Result(showLogo = false, top = 73, headerToGrid = 75), compute(562, 260))
    }

    @Test
    fun `short window keeps minimum gaps and leaves the grid to shrink`() {
        assertEquals(Result(showLogo = false, top = 32, headerToGrid = 48), compute(364, 260))
    }

    @Test
    fun `header to grid gap stops growing at 80`() {
        
        assertEquals(Result(showLogo = true, top = 210, headerToGrid = 80), compute(1000, 260))
    }

    @Test
    fun `screen without a logo never shows one`() {
        assertEquals(Result(showLogo = false, top = 290, headerToGrid = 80), compute(1000, 260, logoBlock = 0))
    }
}
