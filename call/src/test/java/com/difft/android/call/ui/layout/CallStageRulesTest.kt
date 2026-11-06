package com.difft.android.call.ui.layout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.difft.android.call.data.CallStatus
import com.difft.android.call.layout.CallType
import com.difft.android.call.layout.Insets
import com.difft.android.call.layout.LayoutInput
import com.difft.android.call.layout.Posture
import com.difft.android.call.layout.Rect
import com.difft.android.call.layout.Size
import com.difft.android.call.ui.actionbar.CallActionBarPlanner
import com.difft.android.call.ui.tabletop.CallTabletopSpec
import com.difft.android.call.ui.tabletop.FedResolution
import com.difft.android.call.ui.tabletop.TabletopGeometry
import com.difft.android.call.ui.tabletop.bottomReserveDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules of the stage and its tabletop and share geometry. */
class CallStageRulesTest {

    @Test
    fun `the ringing overlay shows only while the caller waits`() {
        assertTrue(showsRingOverlay(oneOnOne = true, remoteInRoom = false, callStatus = CallStatus.CALLING, sharing = false))
        assertFalse("a peer reconnecting", showsRingOverlay(true, false, CallStatus.RECONNECTING, false))
        assertFalse("the callee right after answering", showsRingOverlay(true, false, CallStatus.CONNECTED, false))
        assertFalse("answered", showsRingOverlay(true, true, CallStatus.CALLING, false))
        assertFalse("a group", showsRingOverlay(false, false, CallStatus.CALLING, false))
        assertFalse("a share", showsRingOverlay(true, false, CallStatus.CALLING, true))
    }

    private fun input(fold: Rect?, safe: Insets = Insets(top = 24.0)) = LayoutInput(
        window = Size(608.0, 860.0),
        content = Rect(12.0, 100.0, 584.0, 600.0),
        callType = CallType.GROUP,
        participants = emptyList(),
        safe = safe,
        fold = fold,
    )

    @Test
    fun `the share region is above a tabletop fold, the facing side of an off-centre book fold, else the window`() {
        assertEquals(Rect(0.0, 24.0, 608.0, 386.0), shareRegion(input(Rect(0.0, 410.0, 608.0, 40.0))))
        // Book fold at x=180: the right side faces the user.
        assertEquals(Rect(220.0, 0.0, 388.0, 860.0), shareRegion(input(Rect(180.0, 0.0, 40.0, 860.0))))
        assertEquals("a centred book fold keeps the window", Rect(0.0, 0.0, 608.0, 860.0), shareRegion(input(Rect(284.0, 0.0, 40.0, 860.0))))
        assertEquals(Rect(0.0, 0.0, 608.0, 860.0), shareRegion(input(null)))
        assertNull(shareRegion(null))
    }

    @Test
    fun `a filling share pans as far as it reaches past its region`() {
        val region = IntRect(0, 48, 1216, 820)
        assertEquals(Offset(0f, 300f), sharePanSlack(IntRect(0, -252, 1216, 1120), region))
        assertEquals("a fitted share does not pan", Offset.Zero, sharePanSlack(IntRect(0, 100, 1216, 700), region))
    }

    @Test
    fun `a dragged floating window stays inside the stage`() {
        val rect = IntRect(700, 1500, 940, 1928)
        val stage = IntSize(1080, 2000)
        assertEquals(Offset(140f, 72f), clampDrag(rect, Offset(500f, 500f), stage))
        assertEquals(Offset(-700f, -1500f), clampDrag(rect, Offset(-2000f, -2000f), stage))
        assertEquals(Offset(-10f, 20f), clampDrag(rect, Offset(-10f, 20f), stage))
    }

    @Test
    fun `in the tabletop posture the content runs to the window bottom with the controls shown and hidden`() {
        val plan = CallActionBarPlanner.resolve(608, 860, isGroup = true, isLandscape = false)
        // The layout places the console itself (decision 39): only the navigation bar is kept clear.
        val reserve = bottomReserveDp(Posture.TABLETOP, plan, navBottomDp = 16f)
        assertEquals(16, reserve)
        val rects = callContentRects(
            window = Size(608.0, 860.0),
            safe = Insets(top = 24.0),
            statusBandDp = 24.0,
            barBottomReserveDp = reserve,
            navigationBarDp = 0.0,
            rail = null,
            tabletop = true,
            cutouts = emptyList(),
        )
        assertEquals(860.0 - 16.0 - 8.0, rects.shown.y + rects.shown.h, 1e-9)
        assertEquals(860.0 - 16.0 - 8.0, rects.hidden.y + rects.hidden.h, 1e-9)
        assertEquals("book: one row", plan.bottomMarginDp + plan.buttonSizeDp, bottomReserveDp(Posture.BOOK, plan, 0f))
        assertEquals(plan.chromeBottomReserveDp, bottomReserveDp(Posture.FLAT, plan, 0f))
    }

    @Test
    fun `the console goes where the plan put it, else centred at the window bottom`() {
        val placed = Rect(476.0, 479.0, 368.0, 232.0)
        val g = TabletopGeometry(bandTopPx = 820, bandBottomPx = 900, widthPx = 1216, heightPx = 1720, density = 2f, controls = placed)
        assertEquals(placed, g.consoleDp(bottomInsetDp = 16f))
        // No plan rect yet: (608 - 368) / 2 = 120 from the left, 860 - 16 - 232 = 612 from the top.
        assertEquals(Rect(120.0, 612.0, 368.0, CallTabletopSpec.CONSOLE_HEIGHT_DP.toDouble()), g.copy(controls = null).consoleDp(16f))
        val narrow = TabletopGeometry(820, 900, 600, 1720, 2f)
        assertEquals("never wider than the window", 300.0, narrow.consoleDp().w, 1e-9)
    }

    @Test
    fun `the fold gate stays frozen after picture in picture until the window grows back`() {
        val fed = FedResolution()
        fed.kept = TabletopGeometry(820, 900, 1216, 1720, 2f)
        assertTrue(fed.frozen(inPip = true, widthPx = 480, heightPx = 270))
        assertTrue("the flag cleared first", fed.frozen(inPip = false, widthPx = 480, heightPx = 270))
        assertFalse("back to the full window", fed.frozen(inPip = false, widthPx = 1216, heightPx = 1720))
        assertFalse(fed.frozen(inPip = false, widthPx = 480, heightPx = 270))
    }
}
