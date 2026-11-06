// Synced from call-layout bf6b84ecbe, ports/kotlin/src/test/kotlin/org/difft/calllayout/ControllerTest.kt (spec v25), by scripts/sync-apps.ts.
// Do not edit here: change call-layout and sync again.

package com.difft.android.call.layout

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// The controller (decision 32), ported from test/controller.test.ts: the app reports events, the
// controller keeps the state in between and publishes the plan.
class ControllerTest {
    private val cfg = LayoutConfig.DEFAULT
    private val phoneSize = Size(411.0, 923.0)
    private val phoneSafe = Insets(top = 48.0, bottom = 24.0)
    private val shown = Rect(12.0, 104.0, 387.0, 643.0)
    private val hidden = Rect(12.0, 56.0, 387.0, 835.0)

    private class Clock(var t: Double = 0.0)

    private class Setup(val c: CallLayoutController, val list: List<CallMember>, val clock: Clock)

    private fun setup(n: Int, on: (Int) -> Boolean = { false }): Setup {
        val clock = Clock()
        val c = CallLayoutController(now = { clock.t })
        c.setWindow(phoneSize, phoneSafe, null)
        c.setContent(shown, hidden)
        val list = List(n) { i -> CallMember(id = if (i == 0) "me" else "p$i", isLocal = i == 0, cameraOn = on(i)) }
        c.setParticipants(list)
        return Setup(c, list, clock)
    }

    private val CallLayoutController.plan get() = state.value.plan

    private fun ids(c: CallLayoutController) = c.plan?.tiles?.map { it.id } ?: emptyList()

    @Test
    fun youFirstTheRestInTheOrderTheyJoinedRejoiningGoesLast() {
        val (c, list) = setup(4).let { it.c to it.list }
        c.setParticipants(list.reversed())
        assertEquals(listOf("me", "p1", "p2", "p3"), ids(c))
        c.setParticipants(list.filter { it.id != "p1" })
        c.setParticipants(list)
        assertEquals(listOf("me", "p2", "p3", "p1"), ids(c))
    }

    @Test
    fun aCameraShowsFromItsFirstFrameAndAgainOnlyAfterANewOne() {
        val (c, list) = setup(3) { true }.let { it.c to it.list }
        assertNull(c.plan!!.tiles[1].contentMode)
        c.setVideoSize("p1", 1280.0, 720.0)
        assertNotNull(c.plan!!.tiles[1].contentMode)
        c.setParticipants(list.map { if (it.id == "p1") it.copy(cameraOn = false) else it })
        c.setParticipants(list)
        assertNull(c.plan!!.tiles[1].contentMode)
    }

    @Test
    fun aNewAspectReshapesTheCardOnlyAfterAspectSettleMs() {
        val s = setup(3) { true }
        val c = s.c
        c.setVideoSize("p1", 1280.0, 720.0)
        val before = c.plan!!.tiles[1].rect
        s.clock.t = 1000.0
        c.setVideoSize("p1", 720.0, 1280.0)
        s.clock.t = 1000.0 + cfg.aspectSettleMs - 100
        c.tick()
        assertEquals(before, c.plan!!.tiles[1].rect)
        s.clock.t = 1000.0 + cfg.aspectSettleMs
        c.tick()
        assertNotEquals(before, c.plan!!.tiles[1].rect)
    }

    @Test
    fun hidingTheControlsLaysTheSamePageOutLargerAndAShareKeepsItsOwnTap() {
        val c = setup(12).c
        val before = ids(c)
        val card = c.plan!!.grid!!.card.h
        c.toggleControls()
        assertTrue(c.state.value.controlsHidden)
        assertEquals(before, ids(c))
        assertTrue(c.plan!!.grid!!.card.h > card)
        c.toggleControls()
        c.setShare(ScreenShare("p1", 1920.0, 1080.0))
        c.toggleControls()
        assertFalse(c.state.value.controlsHidden)
    }

    @Test
    fun pagesTurnAndClampThePagerSaysWhereTheBarGoes() {
        val c = setup(12).c
        assertEquals(0 to 2, c.state.value.pager!!.let { it.page to it.pages })
        c.turnPage(1)
        assertEquals(1, c.plan!!.grid!!.page)
        assertEquals("me", ids(c)[0])
        c.turnPage(5)
        assertEquals(1, c.plan!!.grid!!.page)
        assertEquals(shown, c.state.value.pager!!.area)
    }

    @Test
    fun aPageTurnHoldsTheSchedulerForPageTurnHoldMs() {
        val s = setup(14) { it == 13 }
        val c = s.c
        c.setVideoSize("p13", 720.0, 1280.0)
        c.turnPage(1)
        var t = 100L
        while (t < cfg.pageTurnHoldMs) {
            s.clock.t = t.toDouble()
            c.tick()
            t += cfg.tickMs
        }
        c.showPage(0)
        assertFalse("nobody moved while turning pages", "p13" in ids(c))
        t = cfg.pageTurnHoldMs
        while (t <= 2 * cfg.pageTurnHoldMs + cfg.promoteVideoMs + cfg.schedulerStepMs) {
            s.clock.t = t.toDouble()
            c.tick()
            t += cfg.tickMs
        }
        assertTrue("the camera came to the first page once the hold was over", "p13" in ids(c))
    }

    // TS counts the listener's calls; a StateFlow publishes by replacing its value, so here a publish
    // is a new value.
    @Test
    fun onlyRealChangesArePublishedAndWindowChangesAreNotAnimated() {
        val s = setup(3)
        val c = s.c
        val first = c.state.value
        s.clock.t = 100.0
        c.tick()
        assertSame(first, c.state.value)
        c.setWindow(Size(923.0, 411.0), phoneSafe, null)
        val turned = c.state.value
        assertNotSame(first, turned)
        assertFalse(turned.animated)
        c.toggleSwap()
        c.setCallType(CallType.ONE_ON_ONE)
        assertSame("three people lay out the same in a 1on1 (decision 40)", turned, c.state.value)
        c.setParticipants(s.list.take(2))
        assertNotSame(turned, c.state.value)
        assertTrue(c.state.value.animated)
    }

    // Decision 33.

    private fun run(s: Setup, from: Long, to: Long) {
        var t = from
        while (t <= to) {
            s.clock.t = t.toDouble()
            s.c.tick()
            t += cfg.tickMs
        }
    }

    @Test
    fun theAppSetsTheControlsHiddenOrShownAndAShareKeepsThemShown() {
        val c = setup(6).c
        c.setControlsHidden(true)
        assertTrue(c.state.value.controlsHidden)
        c.setControlsHidden(true)
        assertTrue("setting it again does not toggle it", c.state.value.controlsHidden)
        c.setShare(ScreenShare("p1", 1920.0, 1080.0))
        assertFalse(c.state.value.controlsHidden)
        c.setShare(null)
        assertTrue("hidden again once the share ends", c.state.value.controlsHidden)
        c.setControlsHidden(false)
        assertFalse(c.state.value.controlsHidden)
    }

    @Test
    fun aCameraOffPage1ComesOntoItWithoutAFirstFrame() {
        val s = setup(12)
        val c = s.c
        assertFalse("p11" in ids(c))
        c.setParticipants(s.list.map { if (it.id == "p11") it.copy(cameraOn = true) else it })
        run(s, 100, 100 + cfg.promoteVideoMs + 2 * cfg.schedulerStepMs)
        assertTrue("promoted on its camera alone", "p11" in ids(c))
        assertNull("still an avatar until its first frame", c.plan!!.tiles.first { it.id == "p11" }.contentMode)
    }

    @Test
    fun aSchedulerHoldKeepsEveryoneInPlaceUntilItIsLetGo() {
        val s = setup(12)
        val c = s.c
        c.setSchedulerHold(true)
        c.setParticipants(s.list.map { if (it.id == "p11") it.copy(cameraOn = true) else it })
        run(s, 100, 3000)
        assertFalse("nobody moved while held", "p11" in ids(c))
        c.setSchedulerHold(false)
        run(s, 3100, 3100 + 2 * cfg.schedulerStepMs)
        assertTrue("p11" in ids(c))
    }

    @Test
    fun a1on1SwapNeedsBothVideosAndEndsWhenACameraGoesOff() {
        val s = setup(2) { true }
        val c = s.c
        c.setCallType(CallType.ONE_ON_ONE)
        fun main() = c.plan?.tiles?.firstOrNull { it.role == TileRole.MAIN }?.id
        c.toggleSwap()
        assertEquals("no floating window yet, so no swap", "p1", main())
        c.setVideoSize("me", 720.0, 1280.0)
        c.setVideoSize("p1", 1280.0, 720.0)
        c.toggleSwap()
        assertEquals("me", main())
        c.setParticipants(s.list.map { if (it.id == "p1") it.copy(cameraOn = false) else it })
        assertEquals("the other person is back in the main view", "p1", main())
        c.setParticipants(s.list)
        c.setVideoSize("p1", 1280.0, 720.0)
        assertEquals("and stays there when the camera comes back", "p1", main())
    }

    @Test
    fun theRoomForOneViewShowsTheOtherPersonThenWhoeverHasSpoken() {
        val s = setup(5)
        val c = s.c
        assertEquals("the first person after you", "p1", c.state.value.focus)
        c.setSpeakers(listOf("p3"))
        run(s, 100, 100 + cfg.promoteSpeakingMs - 2 * cfg.schedulerStepMs)
        assertEquals("not before promoteSpeakingMs", "p1", c.state.value.focus)
        run(s, 100 + cfg.promoteSpeakingMs, 100 + cfg.promoteSpeakingMs + 2 * cfg.schedulerStepMs)
        assertEquals("p3", c.state.value.focus)
        c.setSpeakers(listOf("me"))
        run(s, 3000, 6000)
        assertEquals("kept while nobody else speaks, you included", "p3", c.state.value.focus)
        c.setParticipants(s.list.filter { it.id != "p3" })
        assertEquals("gone: the first person after you", "p1", c.state.value.focus)
        c.setParticipants(s.list.take(1))
        assertEquals("you while alone", "me", c.state.value.focus)
    }

    @Test
    fun startTicksEveryTickMsInTheGivenScopeUntilStop() = runTest {
        val c = CallLayoutController(now = { testScheduler.currentTime.toDouble() })
        c.setWindow(phoneSize, phoneSafe, null)
        c.setContent(shown, hidden)
        c.setParticipants(List(3) { i -> CallMember(id = if (i == 0) "me" else "p$i", isLocal = i == 0, cameraOn = true) })
        c.setVideoSize("p1", 1280.0, 720.0)
        val before = c.plan!!.tiles[1].rect
        c.setVideoSize("p1", 720.0, 1280.0)
        c.start(backgroundScope) { throw it }
        advanceTimeBy(cfg.aspectSettleMs + cfg.tickMs)
        runCurrent()
        assertNotEquals("a tick settled the new aspect", before, c.plan!!.tiles[1].rect)
        c.stop()
        val stopped = c.state.value
        c.setVideoSize("p1", 1280.0, 720.0)
        advanceTimeBy(10 * cfg.aspectSettleMs)
        runCurrent()
        assertSame("no tick after stop", stopped, c.state.value)
    }

    @Test
    fun aTickThatThrowsGoesToOnErrorAndTheTimerGoesOn() = runTest {
        var boom = false
        val c = CallLayoutController(now = {
            if (boom) {
                boom = false
                throw IllegalStateException("boom")
            }
            testScheduler.currentTime.toDouble()
        })
        c.setWindow(phoneSize, phoneSafe, null)
        c.setContent(shown, hidden)
        c.setParticipants(List(3) { i -> CallMember(id = if (i == 0) "me" else "p$i", isLocal = i == 0, cameraOn = true) })
        c.setVideoSize("p1", 1280.0, 720.0)
        val before = c.plan!!.tiles[1].rect
        c.setVideoSize("p1", 720.0, 1280.0)
        val errors = mutableListOf<Exception>()
        c.start(backgroundScope) { errors += it }
        boom = true
        advanceTimeBy(cfg.aspectSettleMs + 2 * cfg.tickMs)
        runCurrent()
        assertEquals(listOf("boom"), errors.map { it.message })
        assertNotEquals("later ticks still settled the new aspect", before, c.plan!!.tiles[1].rect)
        c.stop()
    }

    @Test
    fun nobodyReportedLaysOutNoCardsAndTheSamePeopleAgainLayOutAgain() {
        val s = setup(3)
        s.c.setParticipants(emptyList())
        assertEquals(emptyList<String>(), ids(s.c))
        assertNull(s.c.state.value.focus)
        s.c.setParticipants(s.list)
        assertEquals(listOf("me", "p1", "p2"), ids(s.c))
    }

    @Test
    fun aShareWhoseSizeIsNotKnownYetStaysOn() {
        val c = setup(3).c
        fun share() = c.plan!!.tiles.first { it.role == TileRole.SHARE }.rect
        c.setShare(ScreenShare("p1", 0.0, 0.0))
        assertEquals("on before its first frame", LayoutMode.SHARE, c.plan?.mode)
        assertEquals("16:9 until the size comes", 16.0 / 9, share().w / share().h, 0.02)
        c.setShare(ScreenShare("p1", 1080.0, 2340.0))
        val tall = share()
        c.setShare(ScreenShare("p1", 0.0, 0.0))
        assertEquals("the same share keeps the aspect it had", tall, share())
        c.setShare(ScreenShare("p2", 0.0, 0.0))
        assertEquals("a new sharer starts at 16:9", 16.0 / 9, share().w / share().h, 0.02)
        c.setShare(null)
        assertNotEquals("only null ends it", LayoutMode.SHARE, c.plan?.mode)
    }

    @Test
    fun aFoldThatDoesNotCrossTheWindowCountsAsNone() {
        val c = setup(4).c
        val flat = c.plan
        c.setWindow(phoneSize, phoneSafe, Rect(0.0, -40.0, phoneSize.w, 20.0))
        assertEquals(flat, c.plan)
        assertNull(crossingFold(phoneSize, Rect(0.0, phoneSize.h, phoneSize.w, 0.0)))
        assertNotNull("a hinge without a gap still counts", crossingFold(phoneSize, Rect(205.0, 0.0, 0.0, phoneSize.h)))
    }

    @Test
    fun specVersionAndLastInput() {
        val c = setup(2).c
        assertEquals(LAYOUT_SPEC_VERSION, c.specVersion)
        assertEquals(shown, c.lastInput!!.content)
        assertEquals(listOf("me", "p1"), c.lastInput!!.participants.map { it.id })
    }
}
