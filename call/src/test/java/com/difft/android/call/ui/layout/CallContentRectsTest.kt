package com.difft.android.call.ui.layout

import com.difft.android.call.ui.actionbar.SideRail
import com.difft.android.call.ui.tabletop.TabletopGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.difft.android.call.layout.Insets as LayoutInsets
import com.difft.android.call.layout.Rect as LayoutRect
import com.difft.android.call.layout.Size as LayoutSize

class CallContentRectsTest {

    @Test
    fun `compact window keeps 12 at the sides and clears the title bar and toolbar`() {
        val rects = callContentRects(
            window = LayoutSize(400.0, 800.0),
            safe = LayoutInsets(top = 30.0, bottom = 20.0),
            statusBandDp = 30.0,
            barBottomReserveDp = 120,
            navigationBarDp = 0.0,
            rail = null,
            tabletop = false,
        )
        
        assertEquals(LayoutRect(12.0, 94.0, 376.0, 578.0), rects.shown)
        
        assertEquals(LayoutRect(12.0, 8.0, 376.0, 784.0), rects.hidden)
    }

    @Test
    fun `hidden rect stays above a navigation bar that is shown`() {
        val rects = callContentRects(
            window = LayoutSize(400.0, 800.0),
            safe = LayoutInsets(top = 30.0, bottom = 20.0),
            statusBandDp = 30.0,
            barBottomReserveDp = 120,
            navigationBarDp = 20.0,
            rail = null,
            tabletop = false,
        )
        assertEquals(LayoutRect(12.0, 8.0, 376.0, 764.0), rects.hidden)
    }

    @Test
    fun `shown rect keeps the status band while the status bar is hidden`() {
        val rects = callContentRects(
            window = LayoutSize(400.0, 800.0),
            safe = LayoutInsets(top = 0.0),
            statusBandDp = 30.0,
            barBottomReserveDp = 120,
            navigationBarDp = 0.0,
            rail = null,
            tabletop = false,
        )
        assertEquals(94.0, rects.shown.y, 0.0)
    }

    @Test
    fun `wide window with a rail gives up the rail column and uses the rail bottom`() {
        val rects = callContentRects(
            window = LayoutSize(1000.0, 600.0),
            safe = LayoutInsets(),
            statusBandDp = 24.0,
            barBottomReserveDp = 100,
            navigationBarDp = 0.0,
            rail = SideRail(),
            tabletop = false,
        )
        
        assertEquals(LayoutRect(16.0, 88.0, 892.0, 488.0), rects.shown)
        
        
        assertEquals(LayoutRect(8.0, 8.0, 984.0, 584.0), rects.hidden)
    }

    @Test
    fun `rail column is taken from the left in RTL`() {
        val rects = callContentRects(
            window = LayoutSize(1000.0, 600.0),
            safe = LayoutInsets(),
            statusBandDp = 24.0,
            barBottomReserveDp = 100,
            navigationBarDp = 0.0,
            rail = SideRail(),
            tabletop = false,
            isRtl = true,
        )
        assertEquals(LayoutRect(92.0, 88.0, 892.0, 488.0), rects.shown)
    }

    @Test
    fun `tabletop starts the cards at the top under the floating title bar and keeps the toolbar bottom when hidden`() {
        val rects = callContentRects(
            window = LayoutSize(700.0, 800.0),
            safe = LayoutInsets(top = 30.0, bottom = 20.0),
            statusBandDp = 30.0,
            barBottomReserveDp = 120,
            navigationBarDp = 0.0,
            rail = null,
            tabletop = true,
        )
        
        
        assertEquals(LayoutRect(16.0, 8.0, 668.0, 664.0), rects.shown)
        assertEquals(LayoutRect(8.0, 8.0, 684.0, 664.0), rects.hidden)
    }

    @Test
    fun `side insets of the safe area narrow both rects`() {
        val rects = callContentRects(
            window = LayoutSize(900.0, 400.0),
            safe = LayoutInsets(left = 40.0, right = 20.0),
            statusBandDp = 0.0,
            barBottomReserveDp = 80,
            navigationBarDp = 0.0,
            rail = null,
            tabletop = false,
        )
        assertEquals(56.0, rects.shown.x, 0.0)
        assertEquals(900.0 - 20.0 - 16.0 - 56.0, rects.shown.w, 0.0)
        
        assertEquals(48.0, rects.hidden.x, 0.0)
        assertEquals(900.0 - 20.0 - 8.0 - 48.0, rects.hidden.w, 0.0)
    }

    @Test
    fun `padFold pads 20 across the thin axis`() {
        assertEquals(LayoutRect(0.0, 380.0, 800.0, 40.0), padFold(LayoutRect(0.0, 400.0, 800.0, 0.0), vertical = false))
        assertEquals(LayoutRect(280.0, 0.0, 40.0, 900.0), padFold(LayoutRect(300.0, 0.0, 0.0, 900.0), vertical = true))
    }

    @Test
    fun `foldInWindow keeps a fold inside the window spanning edge to edge`() {
        val window = LayoutSize(800.0, 1000.0)
        assertEquals(LayoutRect(0.0, 480.0, 800.0, 40.0), foldInWindow(LayoutRect(0.0, 480.0, 800.0, 40.0), window))
        assertEquals(LayoutRect(380.0, 0.0, 40.0, 1000.5), foldInWindow(LayoutRect(380.0, 0.0, 40.0, 1000.5), window))
    }

    @Test
    fun `foldInWindow drops a fold outside the window or not spanning it`() {
        val window = LayoutSize(800.0, 1000.0)
        assertNull(foldInWindow(null, window))
        assertNull(foldInWindow(LayoutRect(0.0, 1100.0, 800.0, 40.0), window))
        assertNull(foldInWindow(LayoutRect(0.0, 480.0, 600.0, 40.0), window))
        
        assertNull(foldInWindow(LayoutRect(0.0, 480.0, 1600.0, 40.0), window))
    }

    @Test
    fun `gatedFoldDp converts the debounced fold to padded window dp`() {
        val window = LayoutSize(800.0, 1000.0)
        val half = TabletopGeometry(bandTopPx = 1000, bandBottomPx = 1000, widthPx = 1600, heightPx = 2000, density = 2f)
        assertEquals(LayoutRect(0.0, 480.0, 800.0, 40.0), gatedFoldDp(half, window, navBottomDp = 0f))
        assertNull(gatedFoldDp(null, window, navBottomDp = 0f))
    }

    @Test
    fun `gatedFoldDp reports a vertical fold for the book posture`() {
        val book = TabletopGeometry(bandTopPx = 800, bandBottomPx = 800, widthPx = 1600, heightPx = 2000, density = 2f, horizontal = false)
        assertEquals(LayoutRect(380.0, 0.0, 40.0, 1000.0), gatedFoldDp(book, LayoutSize(800.0, 1000.0), navBottomDp = 0f))
        
        val shortBook = TabletopGeometry(bandTopPx = 800, bandBottomPx = 800, widthPx = 1600, heightPx = 400, density = 2f, horizontal = false)
        assertEquals(LayoutRect(380.0, 0.0, 40.0, 200.0), gatedFoldDp(shortBook, LayoutSize(800.0, 200.0), navBottomDp = 48f))
    }

    @Test
    fun `gatedFoldDp drops a horizontal fold with no room for the dock console below it`() {
        
        val low = TabletopGeometry(bandTopPx = 1480, bandBottomPx = 1480, widthPx = 1600, heightPx = 2000, density = 2f)
        val window = LayoutSize(800.0, 1000.0)
        assertEquals(LayoutRect(0.0, 720.0, 800.0, 40.0), gatedFoldDp(low, window, navBottomDp = 0f))
        assertNull(gatedFoldDp(low, window, navBottomDp = 24f))
        
        val popup = TabletopGeometry(bandTopPx = 1000, bandBottomPx = 1000, widthPx = 1600, heightPx = 1200, density = 2f)
        assertNull(gatedFoldDp(popup, LayoutSize(800.0, 600.0), navBottomDp = 0f))
    }
}
