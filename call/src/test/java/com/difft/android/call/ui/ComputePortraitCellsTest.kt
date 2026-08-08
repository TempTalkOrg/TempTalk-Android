package com.difft.android.call.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [computePortraitCells] — the pure sizing function backing the ≤6-person
 * fixed portrait layout in `MultiParticipantCallPage.kt`.
 *
 * Design rules (390px portrait phone, see `Mobile-meeting-grid.html` packLayout):
 *  - 1 person : 1 col / 1 row, fill (full width × full height, not square).
 *  - 2 people : 1 col / 2 rows, full width, height split evenly (not square).
 *  - 3 people : 1 col / 3 rows, locked 1:1 square (side bounded by height).
 *  - 4–6 people: 2 cols / ceil(n/2) rows, locked 1:1 square.
 *
 * A representative portrait stage after padding: 358dp wide × 720dp tall, gap 8dp.
 */
class ComputePortraitCellsTest {

    private val width = 358f
    private val height = 720f
    private val gap = 8f

    private fun compute(count: Int) =
        computePortraitCells(count, width, height, gap)

    // ---------------------------------------------------------------------------------------
    // 1 person — full bleed single cell
    // ---------------------------------------------------------------------------------------
    @Test
    fun `single person fills the whole stage`() {
        val layout = compute(1)
        assertEquals(1, layout.columns)
        assertEquals(1, layout.rows)
        assertEquals(width, layout.cellWidthDp, 0.001f)
        assertEquals(height, layout.cellHeightDp, 0.001f)
    }

    // ---------------------------------------------------------------------------------------
    // 2 people — single column, full width, half height (not square)
    // ---------------------------------------------------------------------------------------
    @Test
    fun `two people split top-bottom full width half height`() {
        val layout = compute(2)
        assertEquals(1, layout.columns)
        assertEquals(2, layout.rows)
        assertEquals(width, layout.cellWidthDp, 0.001f)
        // (720 - 8) / 2 = 356
        assertEquals((height - gap) / 2f, layout.cellHeightDp, 0.001f)
        // Explicitly not square.
        assertTrue(layout.cellWidthDp != layout.cellHeightDp)
    }

    // ---------------------------------------------------------------------------------------
    // 3 people — single column of squares, side bounded by height
    // ---------------------------------------------------------------------------------------
    @Test
    fun `three people stack in one column locked square`() {
        val layout = compute(3)
        assertEquals(1, layout.columns)
        assertEquals(3, layout.rows)
        assertEquals(layout.cellWidthDp, layout.cellHeightDp, 0.001f)
        // Height-bound: (720 - 2*8) / 3 = 234.67, which is < full width 358.
        val expectedSide = (height - gap * 2) / 3f
        assertEquals(expectedSide, layout.cellWidthDp, 0.001f)
        assertTrue(layout.cellWidthDp <= width)
    }

    // ---------------------------------------------------------------------------------------
    // 4 people — 2x2 squares, width-bound on this stage
    // ---------------------------------------------------------------------------------------
    @Test
    fun `four people form two columns two rows of squares`() {
        val layout = compute(4)
        assertEquals(2, layout.columns)
        assertEquals(2, layout.rows)
        assertEquals(layout.cellWidthDp, layout.cellHeightDp, 0.001f)
        val cellByWidth = (width - gap) / 2f          // 175
        val cellByHeight = (height - gap) / 2f        // 356
        assertEquals(minOf(cellByWidth, cellByHeight), layout.cellWidthDp, 0.001f)
        assertEquals(cellByWidth, layout.cellWidthDp, 0.001f)
    }

    // ---------------------------------------------------------------------------------------
    // 5 people — 2 cols, 3 rows (last row has the odd cell handled by the caller centering)
    // ---------------------------------------------------------------------------------------
    @Test
    fun `five people use two columns three rows`() {
        val layout = compute(5)
        assertEquals(2, layout.columns)
        assertEquals(3, layout.rows)
        assertEquals(layout.cellWidthDp, layout.cellHeightDp, 0.001f)
        val cellByWidth = (width - gap) / 2f              // 175
        val cellByHeight = (height - gap * 2) / 3f        // 234.67
        assertEquals(minOf(cellByWidth, cellByHeight), layout.cellWidthDp, 0.001f)
    }

    // ---------------------------------------------------------------------------------------
    // 6 people — 2 cols, 3 rows, full grid of squares
    // ---------------------------------------------------------------------------------------
    @Test
    fun `six people fill two columns three rows`() {
        val layout = compute(6)
        assertEquals(2, layout.columns)
        assertEquals(3, layout.rows)
        assertEquals(layout.cellWidthDp, layout.cellHeightDp, 0.001f)
    }

    // ---------------------------------------------------------------------------------------
    // Squares always fit inside the available area (never overflow width or height).
    // ---------------------------------------------------------------------------------------
    @Test
    fun `square cells never overflow the available area`() {
        for (count in 3..6) {
            val layout = compute(count)
            val totalWidth = layout.cellWidthDp * layout.columns + gap * (layout.columns - 1)
            val totalHeight = layout.cellHeightDp * layout.rows + gap * (layout.rows - 1)
            assertTrue("count=$count width overflow", totalWidth <= width + 0.01f)
            assertTrue("count=$count height overflow", totalHeight <= height + 0.01f)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Degenerate constraints never produce negative sizes.
    // ---------------------------------------------------------------------------------------
    @Test
    fun `tiny area never yields negative sizes`() {
        val layout = computePortraitCells(6, availableWidthDp = 1f, availableHeightDp = 1f, gapDp = gap)
        assertTrue(layout.cellWidthDp >= 0f)
        assertTrue(layout.cellHeightDp >= 0f)
    }

    // ---------------------------------------------------------------------------------------
    // Out-of-range counts are rejected (7+ is handled by the scrolling gallery instead).
    // ---------------------------------------------------------------------------------------
    @Test(expected = IllegalArgumentException::class)
    fun `count above six is rejected`() {
        computePortraitCells(7, width, height, gap)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `count below one is rejected`() {
        computePortraitCells(0, width, height, gap)
    }
}
