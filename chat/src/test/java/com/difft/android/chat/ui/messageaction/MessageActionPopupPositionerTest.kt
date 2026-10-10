package com.difft.android.chat.ui.messageaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [MessageActionPopupPositioner] (#1294).
 *
 * Row rects are literal and already knob-extended. Portrait content `(0,100,1000,2100)` gives the
 * band `108..2092`; landscape content `(0,100,2000,900)` gives `108..892`.
 */
class MessageActionPopupPositionerTest {

    private val positioner = MessageActionPopupPositioner
    private val metrics = PopupMetrics(edgePaddingPx = 8, arrowGapPx = 2, arrowMinInsetPx = 16)
    private val portrait = PopupRect(0, 100, 1000, 2100)
    private val landscape = PopupRect(0, 100, 2000, 900)
    private val w = 700
    private val h = 600
    private val portraitBand = VerticalBand(108, 2092, h, 2)
    private val landscapeBand = VerticalBand(108, 892, h, 2)

    private fun rect(l: Int, t: Int, r: Int, b: Int) = PopupRect(l, t, r, b)

    private fun place(
        input: SelectionAnchorInput,
        content: PopupRect = portrait,
        width: Int = w,
        height: Int = h,
        isOutgoing: Boolean = true,
    ) = positioner.place(input, content, width, height, isOutgoing, metrics)

    // region A — anchor resolution

    @Test
    fun `A1 no rows anchors to bubble`() {
        val bubble = rect(50, 400, 900, 900)
        val a = positioner.resolveAnchor(SelectionAnchorInput(bubble), portraitBand)
        assertEquals(AnchorMode.BUBBLE, a.mode)
        assertEquals(bubble, a.rect)
    }

    @Test
    fun `A2 start row only`() {
        val start = rect(60, 300, 900, 360)
        val a = positioner.resolveAnchor(SelectionAnchorInput(rect(50, 250, 900, 1700), startRow = start), portraitBand)
        assertEquals(AnchorMode.START_ROW_ONLY, a.mode)
        assertEquals(start, a.rect)
    }

    @Test
    fun `A3 end row only`() {
        val end = rect(60, 1500, 400, 1560)
        val a = positioner.resolveAnchor(SelectionAnchorInput(rect(50, 250, 900, 1700), endRow = end), portraitBand)
        assertEquals(AnchorMode.END_ROW_ONLY, a.mode)
        assertEquals(end, a.rect)
    }

    @Test
    fun `A4 identical rows are the same row`() {
        val row = rect(200, 500, 600, 560)
        val a = positioner.resolveAnchor(SelectionAnchorInput(rect(50, 400, 900, 900), row, row), portraitBand)
        assertEquals(AnchorMode.SAME_ROW, a.mode)
        assertEquals(row, a.rect)
    }

    @Test
    fun `A5 same row with unequal heights is the union`() {
        val a = positioner.resolveAnchor(
            SelectionAnchorInput(rect(50, 400, 900, 900), rect(100, 500, 300, 560), rect(250, 490, 700, 570)),
            portraitBand,
        )
        assertEquals(AnchorMode.SAME_ROW, a.mode)
        assertEquals(rect(100, 490, 700, 570), a.rect)
    }

    @Test
    fun `A6 whole selection below`() {
        val a = positioner.resolveAnchor(A6_INPUT, portraitBand)
        assertEquals(AnchorMode.WHOLE_SELECTION_BELOW, a.mode)
        assertEquals(rect(60, 400, 500, 760), a.rect)
    }

    @Test
    fun `A7 whole selection above`() {
        val a = positioner.resolveAnchor(A7_INPUT, portraitBand)
        assertEquals(AnchorMode.WHOLE_SELECTION_ABOVE, a.mode)
        assertEquals(rect(100, 800, 900, 1660), a.rect)
    }

    @Test
    fun `A8 first row fallback for the 1294 geometry`() {
        val a = positioner.resolveAnchor(
            SelectionAnchorInput(S1_BUBBLE, S1_START, S1_END),
            VerticalBand(278, 1482, 560, 2),
        )
        assertEquals(AnchorMode.FIRST_ROW, a.mode)
        assertEquals(S1_START, a.rect)
    }

    @Test
    fun `A9 top and bottom are chosen by geometry not by label`() {
        val swapped = A6_INPUT.copy(startRow = A6_INPUT.endRow, endRow = A6_INPUT.startRow)
        assertEquals(positioner.resolveAnchor(A6_INPUT, portraitBand), positioner.resolveAnchor(swapped, portraitBand))
    }

    @Test
    fun `A10 touching rows are not the same row`() {
        val a = positioner.resolveAnchor(
            SelectionAnchorInput(rect(50, 400, 900, 900), rect(100, 500, 900, 560), rect(60, 560, 400, 620)),
            portraitBand,
        )
        assertEquals(AnchorMode.WHOLE_SELECTION_BELOW, a.mode)
    }

    @Test
    fun `A11 row anchor is independent of a taller bubble`() {
        val end = rect(60, 1200, 400, 1260)
        val a = positioner.resolveAnchor(SelectionAnchorInput(rect(40, 300, 960, 1800), endRow = end), portraitBand)
        assertEquals(AnchorMode.END_ROW_ONLY, a.mode)
        assertEquals(end, a.rect)
    }

    // endregion

    // region N — row clip

    @Test
    fun `N1 start row fully above content is dropped`() {
        val clipped = positioner.clipRows(SelectionAnchorInput(rect(0, 0, 10, 10), startRow = rect(60, 60, 900, 99)), portrait)
        assertNull(clipped.startRow)
    }

    @Test
    fun `N2 partial start row is clipped`() {
        val clipped = positioner.clipRows(SelectionAnchorInput(rect(0, 0, 10, 10), startRow = rect(60, 80, 900, 140)), portrait)
        assertEquals(rect(60, 100, 900, 140), clipped.startRow)
    }

    @Test
    fun `N3 end row fully below content is dropped`() {
        val clipped = positioner.clipRows(SelectionAnchorInput(rect(0, 0, 10, 10), endRow = rect(60, 2101, 400, 2130)), portrait)
        assertNull(clipped.endRow)
    }

    @Test
    fun `N4 rows inside content and the bubble are unchanged`() {
        val input = SelectionAnchorInput(rect(0, 50, 1000, 2200), rect(60, 300, 900, 360), rect(60, 1500, 400, 1560))
        assertEquals(input, positioner.clipRows(input, portrait))
    }

    // endregion

    // region Y — vertical placement

    private fun placeY(anchor: PopupRect, bubble: PopupRect = anchor, band: VerticalBand = portraitBand) =
        positioner.placeY(anchor, bubble, band)

    @Test
    fun `Y1 fits below`() {
        assertEquals(YPlacement(902, true, PlacementMode.BELOW), placeY(rect(50, 400, 900, 900)))
    }

    @Test
    fun `Y2 inclusive below boundary`() {
        assertEquals(YPlacement(1492, true, PlacementMode.BELOW), placeY(rect(50, 800, 900, 1490)))
    }

    @Test
    fun `Y3 above when below fails by one pixel`() {
        assertEquals(YPlacement(198, false, PlacementMode.ABOVE), placeY(rect(50, 800, 900, 1491)))
    }

    @Test
    fun `Y4 inclusive above boundary`() {
        assertEquals(YPlacement(108, false, PlacementMode.ABOVE), placeY(rect(50, 710, 900, 1600)))
    }

    @Test
    fun `Y5 pins bottom when the top pin covers more bubble`() {
        assertEquals(YPlacement(1492, true, PlacementMode.PINNED_BOTTOM), placeY(rect(50, 300, 900, 1700)))
    }

    @Test
    fun `Y6 pins top when the bottom pin covers more bubble`() {
        assertEquals(YPlacement(108, false, PlacementMode.PINNED_TOP), placeY(rect(50, 500, 900, 1900)))
    }

    @Test
    fun `Y7 full tie pins bottom`() {
        assertEquals(YPlacement(1492, true, PlacementMode.PINNED_BOTTOM), placeY(portrait))
    }

    @Test
    fun `Y8 bubble tie falls back to anchor overlap`() {
        val y = placeY(rect(60, 700, 500, 750), bubble = landscape, band = landscapeBand)
        assertEquals(YPlacement(108, false, PlacementMode.PINNED_TOP), y)
    }

    @Test
    fun `Y9 anchor tie-break pins bottom in a short area`() {
        val y = placeY(rect(60, 250, 500, 300), bubble = landscape, band = landscapeBand)
        assertEquals(YPlacement(292, true, PlacementMode.PINNED_BOTTOM), y)
    }

    @Test
    fun `Y10 menu taller than content keeps its top inside`() {
        val y = placeY(landscape, band = VerticalBand(108, 892, 900, 2))
        assertEquals(YPlacement(108, true, PlacementMode.PINNED_BOTTOM), y)
    }

    @Test
    fun `Y11 bubble cut by the header`() {
        assertEquals(YPlacement(1302, true, PlacementMode.BELOW), placeY(rect(50, 100, 900, 1300)))
    }

    @Test
    fun `Y12 bubble cut by the input bar`() {
        assertEquals(YPlacement(798, false, PlacementMode.ABOVE), placeY(rect(50, 1400, 900, 2100)))
    }

    @Test
    fun `Y13 degenerate anchor`() {
        assertEquals(YPlacement(502, true, PlacementMode.BELOW), placeY(rect(0, 500, 0, 500)))
    }

    @Test
    fun `Y14 row anchor changes placement compared with Y5`() {
        val y = placeY(rect(60, 1640, 500, 1700), bubble = rect(50, 300, 900, 1700))
        assertEquals(YPlacement(1038, false, PlacementMode.ABOVE), y)
    }

    @Test
    fun `Y15 slack-only end row falls back to the bubble and stays inside the band`() {
        val p = place(SelectionAnchorInput(rect(50, 1400, 900, 2100), endRow = rect(60, 2101, 400, 2130)))
        assertEquals(AnchorMode.BUBBLE, p.anchor.mode)
        assertEquals(PlacementMode.ABOVE, p.placementMode)
        assertEquals(798, p.y)
        assertTrue(p.y + h <= 2092)
    }

    @Test
    fun `Y16 slack-only start row falls back to the bubble and stays inside the band`() {
        val p = place(SelectionAnchorInput(rect(50, 100, 900, 1300), startRow = rect(60, 60, 900, 99)))
        assertEquals(AnchorMode.BUBBLE, p.anchor.mode)
        assertEquals(PlacementMode.BELOW, p.placementMode)
        assertEquals(1302, p.y)
        assertTrue(p.y >= 108)
    }

    @Test
    fun `Y17 top sliver row keeps the menu next to it at minY`() {
        val p = place(SelectionAnchorInput(rect(50, 100, 900, 1300), startRow = rect(60, 60, 900, 104)))
        assertEquals(AnchorMode.START_ROW_ONLY, p.anchor.mode)
        assertEquals(rect(60, 100, 900, 104), p.anchor.rect)
        assertEquals(PlacementMode.BELOW, p.placementMode)
        assertEquals(108, p.y)
    }

    @Test
    fun `Y18 bottom sliver row keeps the menu next to it at maxY`() {
        val p = place(SelectionAnchorInput(rect(50, 1400, 900, 2100), endRow = rect(60, 2096, 400, 2140)))
        assertEquals(AnchorMode.END_ROW_ONLY, p.anchor.mode)
        assertEquals(rect(60, 2096, 400, 2100), p.anchor.rect)
        assertEquals(PlacementMode.ABOVE, p.placementMode)
        assertEquals(1492, p.y)
        assertEquals(2092, p.y + h)
    }

    @Test
    fun `Y19 keyboard-shortened content pins top`() {
        val p = place(SelectionAnchorInput(rect(50, 700, 900, 1000)), content = rect(0, 100, 1000, 1100))
        assertEquals(PlacementMode.PINNED_TOP, p.placementMode)
        assertEquals(108, p.y)
        assertTrue(p.y + h <= 1092)
    }

    @Test
    fun `Y20 full overlap tie uses the free-space rule`() {
        val y = placeY(rect(50, 1100, 900, 1200), band = VerticalBand(108, 2092, 1100, 2))
        assertEquals(YPlacement(108, false, PlacementMode.PINNED_TOP), y)
    }

    // endregion

    // region P — end-to-end X and arrow (placeX / arrowOffsetX rows: MessageActionPopupPositionerXTest)

    @Test
    fun `P7 placement is translation invariant in y`() {
        val bubble = rect(50, 300, 900, 1700)
        val base = place(SelectionAnchorInput(bubble))
        val shifted = place(SelectionAnchorInput(bubble.shiftY(900)), content = portrait.shiftY(900))
        assertEquals(base.y + 900, shifted.y)
        assertEquals(base.x, shifted.x)
        assertEquals(base.placementMode, shifted.placementMode)
        assertEquals(base.anchor.mode, shifted.anchor.mode)
    }

    @Test
    fun `P12 arrow reads the resolved anchor`() {
        val p = place(A6_INPUT.copy(bubble = rect(50, 350, 900, 800)))
        assertEquals(AnchorMode.WHOLE_SELECTION_BELOW, p.anchor.mode)
        assertEquals(200, p.x)
        assertEquals((60 + 500) / 2 - 200, p.arrowOffsetXPx)
        assertEquals(80, p.arrowOffsetXPx)
    }

    @Test
    fun `P14 x follows the bubble and not the resolved selection anchor`() {
        // S1: incoming, bubble.left = 72 while the anchored start row begins at 95.
        val p = s1Placement()
        assertEquals(S1_START, p.anchor.rect)
        assertEquals(72, p.x)
    }

    // endregion

    // region S — #1294 regression

    @Test
    fun `S1 the 1294 geometry places below the first selected line`() {
        val p = s1Placement()
        assertEquals(AnchorMode.FIRST_ROW, p.anchor.mode)
        assertEquals(PlacementMode.BELOW, p.placementMode)
        assertEquals(397, p.y)
        assertTrue(p.isBelow)
    }

    private fun s1Placement() = place(
        SelectionAnchorInput(S1_BUBBLE, S1_START, S1_END),
        content = S1_CONTENT,
        width = 756,
        height = 560,
        isOutgoing = false,
    )

    // endregion

    // region C — handle-coverage contract

    private enum class Row { START, END }

    /** [rows] are the clipped rows; covered means half-open vertical overlap with `[y, y + height)`. */
    private fun assertCoverage(p: PopupPlacement, height: Int, rows: Map<Row, PopupRect>, expectedCovered: Set<Row>) {
        val menu = PopupRect(p.x, p.y, p.x + 1, p.y + height)
        val covered = rows.filterValues { menu.overlapsVertically(it) }.keys
        assertEquals(expectedCovered, covered)
    }

    @Test
    fun `C0 bubble mode covers nothing`() {
        val endRaw = rect(60, 2101, 400, 2130)
        val y15 = place(SelectionAnchorInput(rect(50, 1400, 900, 2100), endRow = endRaw))
        assertEquals(AnchorMode.BUBBLE, y15.anchor.mode)
        assertCoverage(y15, h, mapOf(Row.END to endRaw), emptySet())

        val startRaw = rect(60, 60, 900, 99)
        val y16 = place(SelectionAnchorInput(rect(50, 100, 900, 1300), startRow = startRaw))
        assertEquals(AnchorMode.BUBBLE, y16.anchor.mode)
        assertCoverage(y16, h, mapOf(Row.START to startRaw), emptySet())
    }

    @Test
    fun `C1 same row is clear`() {
        val row = rect(200, 500, 600, 560)
        val p = place(SelectionAnchorInput(rect(50, 400, 900, 900), row, row))
        assertEquals(AnchorMode.SAME_ROW, p.anchor.mode)
        assertEquals(PlacementMode.BELOW, p.placementMode)
        assertEquals(562, p.y)
        assertCoverage(p, h, mapOf(Row.START to row, Row.END to row), emptySet())
    }

    @Test
    fun `C2 start row only is clear`() {
        val start = rect(60, 300, 900, 360)
        val p = place(SelectionAnchorInput(rect(50, 250, 900, 1700), startRow = start))
        assertEquals(AnchorMode.START_ROW_ONLY, p.anchor.mode)
        assertEquals(PlacementMode.BELOW, p.placementMode)
        assertEquals(362, p.y)
        assertCoverage(p, h, mapOf(Row.START to start), emptySet())
    }

    @Test
    fun `C3 end row only is clear`() {
        val end = rect(60, 1500, 400, 1560)
        val p = place(SelectionAnchorInput(rect(50, 250, 900, 1700), endRow = end))
        assertEquals(AnchorMode.END_ROW_ONLY, p.anchor.mode)
        assertEquals(PlacementMode.ABOVE, p.placementMode)
        assertEquals(898, p.y)
        assertCoverage(p, h, mapOf(Row.END to end), emptySet())
    }

    @Test
    fun `C4 whole selection below is clear`() {
        val p = place(A6_INPUT.copy(bubble = rect(50, 350, 900, 800)))
        assertEquals(AnchorMode.WHOLE_SELECTION_BELOW, p.anchor.mode)
        assertEquals(PlacementMode.BELOW, p.placementMode)
        assertEquals(762, p.y)
        assertCoverage(p, h, rowsOf(A6_INPUT), emptySet())
    }

    @Test
    fun `C5 whole selection above is clear`() {
        val p = place(A7_INPUT.copy(bubble = rect(50, 750, 900, 1700)))
        assertEquals(AnchorMode.WHOLE_SELECTION_ABOVE, p.anchor.mode)
        assertEquals(PlacementMode.ABOVE, p.placementMode)
        assertEquals(198, p.y)
        assertCoverage(p, h, rowsOf(A7_INPUT), emptySet())
    }

    @Test
    fun `C6 first row picks the non-covering placement when the gap fits`() {
        val input = SelectionAnchorInput(rect(50, 250, 900, 1600), rect(60, 300, 900, 360), rect(60, 1500, 400, 1560))
        val p = place(input)
        assertEquals(AnchorMode.FIRST_ROW, p.anchor.mode)
        assertEquals(PlacementMode.BELOW, p.placementMode)
        assertEquals(362, p.y)
        assertCoverage(p, h, rowsOf(input), emptySet())
    }

    @Test
    fun `C7 first row below covers only the end row in the 1294 case`() {
        val p = s1Placement()
        assertEquals(AnchorMode.FIRST_ROW, p.anchor.mode)
        assertEquals(PlacementMode.BELOW, p.placementMode)
        assertEquals(397, p.y)
        assertEquals(67, PopupRect(0, p.y, 0, p.y + 560).verticalOverlap(S1_END))
        assertCoverage(p, 560, mapOf(Row.START to S1_START, Row.END to S1_END), setOf(Row.END))
    }

    @Test
    fun `C8 pinned top may cover both rows`() {
        val input = SelectionAnchorInput(rect(50, 250, 900, 800), rect(60, 300, 900, 360), rect(60, 700, 400, 760))
        val p = place(input, content = landscape, isOutgoing = false)
        assertEquals(AnchorMode.FIRST_ROW, p.anchor.mode)
        assertEquals(PlacementMode.PINNED_TOP, p.placementMode)
        assertEquals(108, p.y)
        assertCoverage(p, h, rowsOf(input), setOf(Row.START, Row.END))
    }

    @Test
    fun `C9 pinned bottom may cover a single row`() {
        val start = rect(60, 400, 900, 460)
        val p = place(SelectionAnchorInput(landscape, startRow = start), content = landscape)
        assertEquals(AnchorMode.START_ROW_ONLY, p.anchor.mode)
        assertEquals(PlacementMode.PINNED_BOTTOM, p.placementMode)
        assertEquals(292, p.y)
        assertCoverage(p, h, mapOf(Row.START to start), setOf(Row.START))
    }

    private fun rowsOf(input: SelectionAnchorInput): Map<Row, PopupRect> = buildMap {
        input.startRow?.let { put(Row.START, it) }
        input.endRow?.let { put(Row.END, it) }
    }

    // endregion

    // region F4 — legacy equivalence of the failed-message popup placement

    /**
     * Logic copy of the pre-#1294 `FailedMessageActionPopup.calculatePopupY`, taking [PopupRect]
     * instead of `android.graphics.Rect` and without its unused `arrowHeightPx` parameter.
     */
    private fun legacyCalculatePopupY(
        anchorBounds: PopupRect,
        popupHeight: Int,
        minY: Int,
        maxY: Int,
        arrowGapPx: Int,
    ): Pair<Int, Boolean> {
        val idealYBelow = anchorBounds.bottom + arrowGapPx
        val idealYAbove = anchorBounds.top - popupHeight - arrowGapPx

        val canFitBelow = (idealYBelow + popupHeight) <= maxY
        val canFitAbove = idealYAbove >= minY

        return when {
            canFitBelow -> idealYBelow to true
            canFitAbove -> idealYAbove to false
            else -> {
                val spaceBelow = maxY - anchorBounds.bottom
                val spaceAbove = anchorBounds.top - minY
                if (spaceBelow >= spaceAbove) {
                    idealYBelow.coerceAtMost(maxY - popupHeight) to true
                } else {
                    idealYAbove.coerceAtLeast(minY) to false
                }
            }
        }
    }

    @Test
    fun `F4 bubble-only placement matches the legacy failed popup except documented cells`() {
        val minY = 108
        val maxY = 2092
        val gap = 2
        var compared = 0
        var tallerThanBand = 0
        for (height in listOf(120, 600, 1100, 2100)) {
            for (top in 100..2000 step 100) {
                for (bottom in (top + 100)..2100 step 100) {
                    val bubble = rect(50, top, 900, bottom)
                    val p = place(SelectionAnchorInput(bubble), height = height, isOutgoing = true)
                    val cell = "h=$height bubble=$top..$bottom"
                    assertEquals(cell, AnchorMode.BUBBLE, p.anchor.mode)
                    assertEquals(cell, 900 - w, p.x)
                    val clampWindow = bottom + gap < minY || top - gap > maxY
                    when {
                        height > maxY - minY -> {
                            // Taller-than-content guard: the menu top stays at minY.
                            assertEquals(cell, minY, p.y)
                            tallerThanBand++
                        }
                        clampWindow -> {
                            // Band clamp: the menu stays inside [minY, maxY].
                            assertTrue(cell, p.y >= minY && p.y + height <= maxY)
                        }
                        else -> {
                            val (legacyY, legacyBelow) = legacyCalculatePopupY(bubble, height, minY, maxY, gap)
                            assertEquals(cell, legacyY, p.y)
                            assertEquals(cell, legacyBelow, p.isBelow)
                            compared++
                        }
                    }
                }
            }
        }
        assertTrue(compared > 0)
        assertTrue(tallerThanBand > 0)
    }

    // endregion

    // region PopupRect

    @Test
    fun `PopupRect helpers`() {
        val r = rect(10, 20, 110, 70)
        assertEquals(100, r.width)
        assertEquals(50, r.height)
        assertEquals(60, r.centerX)
        assertFalse(r.overlapsVertically(rect(0, 70, 10, 80)))
        assertEquals(10, r.verticalOverlap(rect(0, 60, 10, 80)))
        assertEquals(0, r.verticalOverlap(rect(0, 80, 10, 90)))
        assertEquals(rect(0, 0, 110, 70), r.union(rect(0, 0, 5, 5)))
        assertNull(r.clipVerticallyTo(rect(0, 70, 10, 90)))
    }

    // endregion

    private fun PopupRect.shiftY(dy: Int) = copy(top = top + dy, bottom = bottom + dy)

    private companion object {
        val A6_INPUT = SelectionAnchorInput(
            bubble = PopupRect(50, 350, 900, 800),
            startRow = PopupRect(100, 400, 900, 460),
            endRow = PopupRect(60, 700, 500, 760),
        )
        val A7_INPUT = SelectionAnchorInput(
            bubble = PopupRect(50, 750, 900, 1700),
            startRow = PopupRect(100, 800, 900, 860),
            endRow = PopupRect(60, 1600, 500, 1660),
        )

        // #1294 screenshot geometry: band 278..1482 with the 8px edge padding.
        val S1_CONTENT = PopupRect(0, 270, 1080, 1490)
        val S1_BUBBLE = PopupRect(72, 280, 1058, 1040)
        val S1_START = PopupRect(95, 330, 580, 395)
        val S1_END = PopupRect(95, 890, 1020, 960)
    }
}
