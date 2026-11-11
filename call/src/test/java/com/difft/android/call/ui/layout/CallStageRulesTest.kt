package com.difft.android.call.ui.layout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.difft.android.call.R
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.layout.CallType
import com.difft.android.call.layout.Insets
import com.difft.android.call.layout.LayoutInput
import com.difft.android.call.layout.Participant
import com.difft.android.call.layout.Posture
import com.difft.android.call.layout.Rect
import com.difft.android.call.layout.Size
import com.difft.android.call.layout.resolveLayout
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


class CallStageRulesTest {

    @Test
    fun `a 1on1 offers no mute menu until it turns into a group or holds a third person`() {
        fun input(type: CallType, n: Int) = LayoutInput(
            window = Size(951.0, 669.0),
            content = Rect(16.0, 8.0, 919.0, 653.0),
            callType = type,
            participants = List(n) { Participant(id = "p$it", isLocal = it == 0, videoOn = false) },
        )
        
        assertFalse(muteMenuAllowed(true, input(CallType.ONE_ON_ONE, 2), oneOnOneCall = true, people = 2))
        assertTrue(muteMenuAllowed(true, input(CallType.GROUP, 2), oneOnOneCall = true, people = 2))
        assertTrue(muteMenuAllowed(true, input(CallType.ONE_ON_ONE, 3), oneOnOneCall = true, people = 3))
        assertFalse(muteMenuAllowed(false, input(CallType.GROUP, 4), oneOnOneCall = false, people = 4))
        
        assertFalse(muteMenuAllowed(true, null, oneOnOneCall = true, people = 2))
        assertTrue(muteMenuAllowed(true, null, oneOnOneCall = false, people = 2))
    }

    @Test
    fun `the ringing overlay shows only while the caller waits`() {
        assertTrue(showsRingOverlay(oneOnOne = true, remoteInRoom = false, callStatus = CallStatus.CALLING, sharing = false))
        assertFalse("a peer reconnecting", showsRingOverlay(true, false, CallStatus.RECONNECTING, false))
        assertFalse("the callee right after answering", showsRingOverlay(true, false, CallStatus.CONNECTED, false))
        assertFalse("answered", showsRingOverlay(true, true, CallStatus.CALLING, false))
        assertFalse("a group", showsRingOverlay(false, false, CallStatus.CALLING, false))
        assertFalse("a share", showsRingOverlay(true, false, CallStatus.CALLING, true))
    }

    @Test
    fun `the ringing overlay centres the callee in your own card, above a level fold`() {
        val you = listOf(Participant(id = "me", isLocal = true, videoOn = false))
        val ringing = { fold: Rect? -> input(fold).copy(callType = CallType.ONE_ON_ONE, participants = you) }
        val above = ringArea(resolveLayout(ringing(Rect(0.0, 410.0, 608.0, 40.0))))!!
        assertTrue("above the fold: $above", above.y + above.h <= 410.0)
        assertEquals("flat: the whole window", Rect(0.0, 0.0, 608.0, 860.0), ringArea(resolveLayout(ringing(null))))
        assertNull("before a layout: the whole stage", ringArea(null))
    }

    @Test
    fun `a hang-up before the callee answers keeps the ringing overlay where it was while the page closes`() {
        val ring = RingMemory()
        val you = listOf(Participant(id = "me", isLocal = true, videoOn = false))
        val plan = resolveLayout(input(Rect(0.0, 410.0, 608.0, 40.0)).copy(callType = CallType.ONE_ON_ONE, participants = you))
        assertTrue(ring.shows(oneOnOne = true, remoteInRoom = false, callStatus = CallStatus.CALLING, sharing = false))
        val placed = ring.place(plan, Offset.Zero, 2f)
        assertTrue("hung up: kept", ring.shows(oneOnOne = true, remoteInRoom = false, callStatus = CallStatus.DISCONNECTED, sharing = false))
        assertEquals("the layout let your card go: where it was", placed, ring.place(null, Offset.Zero, 2f))
        assertFalse("disconnected without having rung", RingMemory().shows(true, false, CallStatus.DISCONNECTED, false))
        assertFalse("reconnecting", ring.shows(true, false, CallStatus.RECONNECTING, false))
    }

    @Test
    fun `the callee waits for the plan to have the posture the fold settled on`() {
        val area = IntRect(0, 48, 1216, 772)
        assertFalse("the fold not heard yet", ringReady(RingPlacement(area, Posture.FLAT), null))
        assertFalse("a flat plan, the fold settled half open", ringReady(RingPlacement(area, Posture.FLAT), Posture.TABLETOP))
        assertTrue(ringReady(RingPlacement(area, Posture.TABLETOP), Posture.TABLETOP))
        assertTrue("no fold: flat", ringReady(RingPlacement(area, Posture.FLAT), Posture.FLAT))
        assertFalse("no layout yet", ringReady(RingPlacement(null, null), Posture.FLAT))
    }

    @Test
    fun `names and avatars already shown stay when the cache empties at the end of the session`() {
        val callee = CallUserDisplayInfo(id = "callee", name = "luke-pixel6", avatarData = null)
        val seen = keepDisplayInfos(emptyMap(), mapOf("callee" to callee))
        assertEquals("the session ended: kept", callee, keepDisplayInfos(seen, emptyMap())["callee"])
        val renamed = callee.copy(name = "Bob")
        assertEquals("an update wins", renamed, keepDisplayInfos(seen, mapOf("callee" to renamed))["callee"])
    }

    @Test
    fun `picture in picture keeps saying calling while a hang-up closes the page`() {
        assertEquals(R.string.call_status_calling, ringPipLabel(CallStatus.CALLING))
        assertEquals("kept through the hang-up", R.string.call_status_calling, ringPipLabel(CallStatus.DISCONNECTED))
        assertEquals(R.string.call_connecting_title, ringPipLabel(CallStatus.JOINING))
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
