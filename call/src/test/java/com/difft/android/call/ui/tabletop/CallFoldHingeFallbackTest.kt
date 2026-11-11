package com.difft.android.call.ui.tabletop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test






class CallFoldHingeFallbackTest {

    private val vertical = CallFold(CallFoldState.FLAT, horizontal = false, occluding = false, bounds = PxRect(1156, 0, 1156, 2504))
    private val horizontal = CallFold(CallFoldState.FLAT, horizontal = true, occluding = false, bounds = PxRect(0, 1156, 2504, 1156))

    private fun report(vararg folds: CallFold) = CallFoldReport(folds.toList())

    @Test
    fun `a flat vertical fold inside the half-open band becomes half open by the hinge`() {
        val out = report(vertical).withHingeFallback(110f)
        assertEquals(report(vertical.copy(state = CallFoldState.HALF_OPENED, byHinge = true)), out)
    }

    @Test
    fun `the band includes both edges`() {
        assertEquals(CallFoldState.HALF_OPENED, report(vertical).withHingeFallback(HingeFallback.HALF_OPEN_MIN_DEG).folds[0].state)
        assertEquals(CallFoldState.HALF_OPENED, report(vertical).withHingeFallback(HingeFallback.HALF_OPEN_MAX_DEG).folds[0].state)
    }

    @Test
    fun `angles outside the band leave the fold flat`() {
        for (deg in listOf(0f, 25f, 155f, 175f, 180f)) {
            val r = report(vertical)
            assertSame("hinge=$deg", r, r.withHingeFallback(deg))
        }
    }

    @Test
    fun `a fold already half open by the hinge holds until the angle leaves the wider band`() {
        val half = CallFoldState.HALF_OPENED
        
        assertEquals(half, report(vertical).withHingeFallback(155f, heldByHinge = true).folds[0].state)
        assertEquals(half, report(vertical).withHingeFallback(160f, heldByHinge = true).folds[0].state)
        assertEquals(CallFoldState.FLAT, report(vertical).withHingeFallback(165f, heldByHinge = true).folds[0].state)
        assertEquals(half, report(vertical).withHingeFallback(20f, heldByHinge = true).folds[0].state)
        assertEquals(CallFoldState.FLAT, report(vertical).withHingeFallback(15f, heldByHinge = true).folds[0].state)
        
        assertEquals(CallFoldState.FLAT, report(vertical).withHingeFallback(155f, heldByHinge = false).folds[0].state)
    }

    @Test
    fun `a horizontal fold keeps the system's state`() {
        val r = report(horizontal)
        assertSame(r, r.withHingeFallback(90f))
    }

    @Test
    fun `a fold the system already reports half open is left as reported`() {
        val r = report(vertical.copy(state = CallFoldState.HALF_OPENED))
        assertSame(r, r.withHingeFallback(90f))
    }

    @Test
    fun `no hinge sensor leaves the report as is`() {
        val r = report(vertical)
        assertSame(r, r.withHingeFallback(null))
        val none = report()
        assertSame(none, none.withHingeFallback(90f))
    }

    @Test
    fun `only the flat vertical folds of a report change`() {
        val out = report(horizontal, vertical).withHingeFallback(90f)
        assertEquals(report(horizontal, vertical.copy(state = CallFoldState.HALF_OPENED, byHinge = true)), out)
    }

    @Test
    fun `the log line marks a fold read from the hinge and gives the angle`() {
        val out = report(vertical).withHingeFallback(110f)
        assertEquals(
            "[Call] fold n=1 f0=HALF_OPENED/V/occl=false/1156,0,1156,2504/byHinge winPx=2312x2504 hinge=110",
            formatFoldLog(out, 2312, 2504, 110f),
        )
        assertEquals(
            "[Call] fold n=1 f0=FLAT/V/occl=false/1156,0,1156,2504 winPx=2312x2504 hinge=175",
            formatFoldLog(report(vertical), 2312, 2504, 175f),
        )
    }
}
