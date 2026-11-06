package com.difft.android.call.ui.layout

import com.difft.android.call.layout.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

private const val EPS = 1e-9

/** Guide §5.1 page indicator geometry. */
class CallPagerGeometryTest {

    private val area = Rect(x = 16.0, y = 100.0, w = 360.0, h = 600.0)

    @Test
    fun `a few pages keep full segments, centred, right edge 4 inside the area`() {
        val bar = pagerBar(area, pages = 3)
        assertEquals(36.0, bar.spacing, EPS)
        assertEquals(32.0, bar.segment, EPS)
        assertEquals("right edge", area.x + area.w - 4.0, bar.x + 3.0, EPS)
        val height = 3 * 32.0 + 2 * 4.0
        assertEquals("centred vertically", area.y + (area.h - height) / 2, bar.top, EPS)
        assertEquals(bar.top + 2 * 36.0, bar.segmentTop(2), EPS)
    }

    @Test
    fun `many pages compress spacing to (h + 4) over pages and the gap to a quarter of it`() {
        val short = Rect(0.0, 0.0, 300.0, 296.0)
        val bar = pagerBar(short, pages = 20)
        val spacing = (296.0 + 4.0) / 20
        assertEquals(spacing, bar.spacing, EPS)
        assertEquals("gap is min(4, spacing / 4)", spacing - spacing / 4, bar.segment, EPS)
        val height = 20 * spacing - spacing / 4
        assertEquals(short.y + (short.h - height) / 2, bar.top, EPS)
        assertEquals("the bar spans the area's height plus 4 less one gap", short.h + 4.0 - spacing / 4, bar.segmentTop(19) + bar.segment - bar.top, EPS)
    }

    @Test
    fun `the gap stays 4 until the spacing drops under 16`() {
        val bar = pagerBar(Rect(0.0, 0.0, 300.0, 196.0), pages = 10)
        assertEquals(20.0, bar.spacing, EPS)
        assertEquals(16.0, bar.segment, EPS)
    }

    @Test
    fun `the pill sits 6 left of the bar, centred on the current segment`() {
        val bar = pagerBar(area, pages = 4)
        val anchor = pagerPillAnchor(bar, page = 2)
        assertEquals(bar.x - 6.0, anchor.x, EPS)
        assertEquals(bar.segmentTop(2) + bar.segment / 2, anchor.y, EPS)
    }

    @Test
    fun `the page pill rect ends at the anchor and is centred on the current segment`() {
        val bar = pagerBar(area, pages = 4)
        val pill = pagerPillRect(bar, page = 1, w = 24.0, h = 20.0)
        assertEquals(bar.x - 6.0, pill.x + pill.w, EPS)
        assertEquals(bar.segmentCenterY(1), pill.y + pill.h / 2, EPS)
    }

    @Test
    fun `the last card's label sits top right of the card, inset 8, and never right of the bar less 6`() {
        val bar = pagerBar(area, pages = 4)
        val farPill = Rect(0.0, 0.0, 0.0, 0.0)
        // A card ending well left of the bar: inset 8 from its own right edge.
        val narrow = lastTileLabelRect(Rect(16.0, 300.0, 200.0, 100.0), bar, farPill, w = 60.0, h = 20.0)
        assertEquals(16.0 + 200.0 - 8.0, narrow.x + narrow.w, EPS)
        assertEquals(308.0, narrow.y, EPS)
        // A card reaching the area's right edge: capped at the bar's left edge less 6.
        val wide = lastTileLabelRect(Rect(16.0, 300.0, 360.0, 100.0), bar, farPill, w = 60.0, h = 20.0)
        assertEquals(bar.x - 6.0, wide.x + wide.w, EPS)
    }

    @Test
    fun `the last card's label moves left of the page pill when they would overlap`() {
        val bar = pagerBar(area, pages = 4)
        val card = Rect(16.0, 300.0, 360.0, 100.0)
        val pill = pagerPillRect(bar, page = 1, w = 24.0, h = 20.0)
        // A card whose top-right corner is where the pill sits.
        val cardAtPill = card.copy(y = pill.y - 8.0)
        val label = lastTileLabelRect(cardAtPill, bar, pill, w = 60.0, h = 20.0)
        assertEquals("right edge 6 left of the pill", pill.x - 6.0, label.x + label.w, EPS)
        // Far from the pill: unchanged.
        val away = lastTileLabelRect(card.copy(y = pill.y + 200.0), bar, pill, w = 60.0, h = 20.0)
        assertEquals(bar.x - 6.0, away.x + away.w, EPS)
    }

    @Test
    fun `each segment's tap target is 14 wide, centred on the bar, as tall as its spacing`() {
        val bar = pagerBar(area, pages = 3)
        val tap = bar.tapRect(1)
        assertEquals(14.0, tap.w, EPS)
        assertEquals(bar.x + 1.5, tap.x + tap.w / 2, EPS)
        assertEquals(bar.spacing, tap.h, EPS)
        assertEquals("targets tile the bar without overlap", bar.tapRect(0).y + bar.spacing, tap.y, EPS)
    }

    @Test
    fun `a swipe longer than the threshold turns a page, up is next`() {
        assertEquals(0, pageTurnFor(-40f, 40f))
        assertEquals(1, pageTurnFor(-41f, 40f))
        assertEquals(-1, pageTurnFor(41f, 40f))
        assertEquals(0, pageTurnFor(10f, 40f))
    }
}
