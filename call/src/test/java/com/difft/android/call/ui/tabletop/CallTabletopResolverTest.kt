package com.difft.android.call.ui.tabletop

import com.difft.android.call.layout.LayoutMode
import com.difft.android.call.layout.LayoutPlan
import com.difft.android.call.layout.Orientation
import com.difft.android.call.layout.Posture
import com.difft.android.call.layout.Rect
import com.difft.android.call.layout.RequestOrientation
import com.difft.android.call.layout.Size
import com.difft.android.call.layout.WindowClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test






class CallTabletopResolverTest {

    private fun fold(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        state: CallFoldState = CallFoldState.HALF_OPENED,
        horizontal: Boolean = true,
    ) = CallFold(state, horizontal, occluding = false, bounds = PxRect(left, top, right, bottom))

    private fun resolve(vararg folds: CallFold, width: Int, height: Int, density: Float = 2f, left: Int = 0, top: Int = 0) =
        resolveTabletop(CallFoldReport(folds.toList()), left, top, width, height, density)

    private fun accepted(r: TabletopResolution): TabletopGeometry {
        assertTrue("expected Accepted, got $r", r is TabletopResolution.Accepted)
        return (r as TabletopResolution.Accepted).geometry
    }

    @Test
    fun `a half-open horizontal fold is accepted at its own bounds`() {
        val g = accepted(resolve(fold(0, 1182, 1672, 1182), width = 1672, height = 2364, density = 2.75f))
        assertEquals(TabletopGeometry(1182, 1182, 1672, 2364, 2.75f, horizontal = true), g)
        assertEquals("the same report compares equal", g, accepted(resolve(fold(0, 1182, 1672, 1182), width = 1672, height = 2364, density = 2.75f)))
    }

    @Test
    fun `a fold the old rules rejected is accepted, the layout decides`() {
        
        accepted(resolve(fold(0, 1182, 1168, 1182), width = 1168, height = 1712, density = 2.75f))
        
        accepted(resolve(fold(0, 500, 600, 500), width = 1000, height = 1000))
    }

    @Test
    fun `a vertical half-open fold is the book posture`() {
        val g = accepted(resolve(fold(1182, 0, 1182, 1672, horizontal = false), width = 2364, height = 1672, density = 2.75f))
        assertEquals(false, g.horizontal)
        assertEquals(1182, g.bandTopPx)
        val dp = g.foldDp()
        assertEquals(1182 / 2.75, dp.x, 0.01)
        assertEquals(0.0, dp.y, 0.0)
        assertEquals("spans the window's height", 1672 / 2.75, dp.h, 0.01)
    }

    @Test
    fun `fold bounds are converted to root coordinates`() {
        val g = accepted(resolve(fold(100, 600, 1100, 640), width = 1000, height = 1000, left = 100, top = 50))
        assertEquals(550, g.bandTopPx)
        assertEquals(590, g.bandBottomPx)
    }

    @Test
    fun `no report, no feature or a fold outside the root is no fold`() {
        assertEquals(TabletopResolution.NoFold, resolveTabletop(null, 0, 0, 1000, 1000, 2f))
        assertEquals(TabletopResolution.NoFold, resolve(width = 1000, height = 1000))
        assertEquals(TabletopResolution.NoFold, resolve(fold(0, 1200, 1000, 1200), width = 1000, height = 1000))
        assertEquals(TabletopResolution.NoFold, resolve(fold(0, 500, 1000, 500), width = 0, height = 1000))
    }

    @Test
    fun `a flat fold carries the root size, in either orientation`() {
        assertEquals(TabletopResolution.Flat(1000, 900), resolve(fold(0, 450, 1000, 450, CallFoldState.FLAT), width = 1000, height = 900))
        assertEquals(
            TabletopResolution.Flat(1000, 900),
            resolve(fold(500, 0, 500, 900, CallFoldState.FLAT, horizontal = false), width = 1000, height = 900),
        )
    }

    @Test
    fun `the first half-open fold crossing the root wins`() {
        val g = accepted(resolve(fold(0, 300, 1000, 300, CallFoldState.FLAT), fold(0, 600, 1000, 600), fold(0, 800, 1000, 800), width = 1000, height = 1000))
        assertEquals(600, g.bandTopPx)
    }

    private fun plan(posture: Posture, dock: Rect?, controls: Rect? = null) = LayoutPlan(
        WindowClass.COMPACT, Orientation.PORTRAIT, RequestOrientation.PORTRAIT, LayoutMode.GRID, posture, emptyList(), null, dock, null, controls,
    )

    @Test
    fun `the drawn tabletop geometry comes from the plan's fold and dock`() {
        val fold = Rect(0.0, 410.0, 608.0, 40.0)
        val g = planTabletop(plan(Posture.TABLETOP, Rect(0.0, 458.0, 608.0, 300.0)), fold, Size(608.0, 860.0), 2f)!!
        assertEquals(410f, g.bandTopDp, 0.01f)
        assertEquals("the dock starts where the plan's does", 458f, g.dockTopDp, 0.01f)
        assertEquals(860f - 458f, g.dockHeightDp, 0.01f)
        assertNull("no console size reported", g.controls)
        val controls = Rect(120.0, 612.0, 368.0, 232.0)
        val placed = planTabletop(plan(Posture.TABLETOP, Rect(0.0, 458.0, 608.0, 146.0), controls), fold, Size(608.0, 860.0), 2f)!!
        assertEquals("the console where the plan put it", controls, placed.consoleDp())
        assertNull("flat", planTabletop(plan(Posture.FLAT, null), fold, Size(608.0, 860.0), 2f))
        assertNull("book", planTabletop(plan(Posture.BOOK, null), fold, Size(608.0, 860.0), 2f))
    }
}
