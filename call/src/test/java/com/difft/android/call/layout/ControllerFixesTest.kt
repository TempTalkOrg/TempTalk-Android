package com.difft.android.call.layout

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local fixes to the synced controller (TempTalk Android); each one is reported back to call-layout. */
class ControllerFixesTest {
    private val cfg = LayoutConfig.DEFAULT
    private val phoneSize = Size(411.0, 923.0)
    private val phoneSafe = Insets(top = 48.0, bottom = 24.0)
    private val shown = Rect(12.0, 104.0, 387.0, 643.0)

    private fun members(n: Int) = List(n) { i -> CallMember(id = if (i == 0) "me" else "p$i", isLocal = i == 0, cameraOn = false) }

    private fun controller(): CallLayoutController = CallLayoutController(now = { 0.0 }).apply {
        setWindow(phoneSize, phoneSafe, null)
        setContent(shown)
    }

    @Test
    fun anEmptyListClearsTheStateAndTheSameListPublishesAgain() {
        val c = controller()
        c.setParticipants(members(2))
        val first = c.state.value
        assertNotNull(first.plan)
        c.setParticipants(emptyList())
        // Spec v20: nobody reported lays out a plan without cards (not a null plan).
        assertTrue(c.state.value.plan!!.tiles.isEmpty())
        assertNull(c.state.value.focus)
        c.setParticipants(members(2))
        assertEquals(first.plan, c.state.value.plan)
        assertEquals("p1", c.state.value.focus)
    }

    @Test
    fun aFoldOutsideTheWindowIsDropped() {
        val c = controller()
        c.setParticipants(members(3))
        // A tabletop fold reported for a taller window than the current one.
        c.setWindow(Size(400.0, 300.0), Insets.ZERO, Rect(0.0, 450.0, 400.0, 40.0))
        assertEquals(Posture.FLAT, c.state.value.plan!!.posture)
        assertNull(c.lastInput!!.fold)
        c.setWindow(Size(400.0, 900.0), Insets.ZERO, Rect(0.0, 450.0, 400.0, 40.0))
        assertEquals(Posture.TABLETOP, c.state.value.plan!!.posture)
    }

    @Test
    fun aDegenerateContentAreaYieldsFiniteNonNegativeRects() {
        val c = controller()
        c.setContent(Rect(12.0, 104.0, 0.0, 4.0))
        c.setParticipants(members(2).map { it.copy(cameraOn = true) })
        c.setVideoSize("me", 720.0, 1280.0)
        c.setVideoSize("p1", 1280.0, 720.0)
        val plan = c.state.value.plan!!
        val rects = plan.tiles.map { it.rect } + listOfNotNull(plan.dock, plan.pipArea)
        rects.forEach { r ->
            assertTrue(r.toString(), listOf(r.x, r.y, r.w, r.h).all { it.isFinite() })
            assertTrue(r.toString(), r.w >= 0 && r.h >= 0)
        }
    }

    @Test
    fun aFailingTickIsReportedAndTheTimerGoesOn() = runTest {
        var failOnce = false
        val c = CallLayoutController(now = {
            if (failOnce) {
                failOnce = false
                error("clock hiccup")
            }
            testScheduler.currentTime.toDouble()
        })
        c.setWindow(phoneSize, phoneSafe, null)
        c.setContent(shown)
        c.setParticipants(members(3).map { it.copy(cameraOn = true) })
        c.setVideoSize("p1", 1280.0, 720.0)
        val before = c.state.value.plan!!.tiles[1].rect
        c.setVideoSize("p1", 720.0, 1280.0)
        val errors = mutableListOf<Throwable>()
        c.start(backgroundScope, onError = { errors += it })
        failOnce = true
        advanceTimeBy(cfg.aspectSettleMs + 3 * cfg.tickMs)
        runCurrent()
        assertEquals(1, errors.size)
        assertTrue("ticks continued after the failure", before != c.state.value.plan!!.tiles[1].rect)
        c.stop()
    }
}
