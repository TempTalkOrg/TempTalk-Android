package com.difft.android.chat.ui.messageaction

import org.junit.Assert.assertEquals
import org.junit.Test








class MessageActionPopupPositionerXTest {

    private val positioner = MessageActionPopupPositioner
    private val metrics = PopupMetrics(edgePaddingPx = 8, arrowGapPx = 2, arrowMinInsetPx = 16)
    private val portrait = PopupRect(0, 100, 1000, 2100)
    private val w = 700
    private val h = 600

    private fun rect(l: Int, t: Int, r: Int, b: Int) = PopupRect(l, t, r, b)

    private fun place(input: SelectionAnchorInput, content: PopupRect, isOutgoing: Boolean) =
        positioner.place(input, content, w, h, isOutgoing, metrics)

    private fun placeX(bubble: PopupRect, isOutgoing: Boolean, content: PopupRect = portrait, width: Int = w) =
        positioner.placeX(bubble, content, width, metrics.edgePaddingPx, isOutgoing)

    @Test
    fun `P1 outgoing aligns with the bubble right edge`() {
        assertEquals(200, placeX(rect(50, 400, 900, 900), isOutgoing = true))
    }

    @Test
    fun `P2 incoming aligns with the bubble left edge`() {
        assertEquals(72, placeX(rect(72, 400, 900, 900), isOutgoing = false))
    }

    @Test
    fun `P3 narrower selection menu keeps the bubble right edge`() {
        assertEquals(480, placeX(rect(50, 400, 900, 900), isOutgoing = true, width = 420))
    }

    @Test
    fun `P4 bubble edge that would push the menu past the padding is clamped`() {
        
        assertEquals(8, placeX(rect(26, 400, 300, 900), isOutgoing = true))
        
        assertEquals(292, placeX(rect(800, 400, 990, 900), isOutgoing = false))
        
        assertEquals(292, placeX(rect(-50, 400, 1100, 900), isOutgoing = true))
        assertEquals(8, placeX(rect(-50, 400, 1100, 900), isOutgoing = false))
    }

    @Test
    fun `P5 dual-pane content bounds`() {
        val pane = rect(1200, 100, 2400, 1700)
        val bubble = rect(1226, 300, 2388, 1600)
        assertEquals(1688, placeX(bubble, isOutgoing = true, content = pane))
        assertEquals(1226, placeX(bubble, isOutgoing = false, content = pane))
        
        assertEquals(1208, placeX(rect(26, 300, 1100, 1600), isOutgoing = true, content = pane))
    }

    @Test
    fun `P6 dual pane end to end`() {
        val pane = rect(1200, 100, 2400, 1700)
        val input = SelectionAnchorInput(rect(1250, 300, 2350, 1600))
        mapOf(true to 1650, false to 1250).forEach { (outgoing, expectedX) ->
            val p = place(input, content = pane, isOutgoing = outgoing)
            assertEquals(PlacementMode.PINNED_TOP, p.placementMode)
            assertEquals(108, p.y)
            assertEquals(expectedX, p.x)
        }
    }

    @Test
    fun `P8 unclamped arrow`() {
        assertEquals(208, positioner.arrowOffsetX(500, 292, 700, 16))
    }

    @Test
    fun `P9 arrow clamps low`() {
        assertEquals(16, positioner.arrowOffsetX(100, 292, 700, 16))
    }

    @Test
    fun `P10 arrow clamps high`() {
        assertEquals(684, positioner.arrowOffsetX(1000, 292, 700, 16))
    }

    @Test
    fun `P11 degenerate width does not throw`() {
        assertEquals(16, positioner.arrowOffsetX(500, 292, 20, 16))
    }

    @Test
    fun `P13 menu wider than the band starts at the left bound`() {
        val bubble = rect(50, 400, 900, 900)
        listOf(true, false).forEach { outgoing ->
            
            assertEquals(8, placeX(bubble, outgoing, width = 984))
            assertEquals(8, placeX(bubble, outgoing, width = 990))
            assertEquals(8, placeX(bubble, outgoing, width = 1200))
        }
    }
}
