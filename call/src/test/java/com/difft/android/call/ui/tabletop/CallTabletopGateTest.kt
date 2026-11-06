package com.difft.android.call.ui.tabletop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TabletopGate]: every cell of the debounce table, plus the field sequences it exists for
 * (opening and closing transients on the Xiaomi 18 Fold, a resize during the flat hysteresis).
 */
class CallTabletopGateTest {

    private val g = TabletopGeometry(bandTopPx = 1127, bandBottomPx = 1237, widthPx = 1672, heightPx = 2364, density = 2.75f)
    private val g2 = g.copy(bandTopPx = 1000, bandBottomPx = 1110)
    private val otherWindow = g.copy(widthPx = 1168, heightPx = 1712)

    private val accepted = TabletopResolution.Accepted(g)
    private val flatSame = TabletopResolution.Flat(g.widthPx, g.heightPx)
    private val flatOther = TabletopResolution.Flat(1672, 1650)

    private fun step(state: GateState, timer: Long?, event: GateEvent?) = GateStep(state, timer, event)

    @Test
    fun `the first report enters at once when the call opens already standing`() {
        assertEquals(step(GateState.On(g), null, GateEvent.Entered(g)), TabletopGate.onInput(GateState.Initial, accepted))
        assertEquals(step(GateState.Off, null, null), TabletopGate.onInput(GateState.Initial, flatSame))
        assertEquals(step(GateState.Off, null, null), TabletopGate.onInput(GateState.Initial, TabletopResolution.NoFold))
        assertEquals(step(GateState.Initial, null, null), TabletopGate.onTimeout(GateState.Initial))
    }

    @Test
    fun `off waits for an accepted geometry`() {
        assertEquals(step(GateState.Pending(g), 600L, null), TabletopGate.onInput(GateState.Off, accepted))
        assertEquals(step(GateState.Off, null, null), TabletopGate.onInput(GateState.Off, flatSame))
        assertEquals(step(GateState.Off, null, null), TabletopGate.onInput(GateState.Off, TabletopResolution.NoFold))
        assertEquals(step(GateState.Off, null, null), TabletopGate.onInput(GateState.Off, TabletopResolution.NoFold))
        assertEquals(step(GateState.Off, null, null), TabletopGate.onTimeout(GateState.Off))
    }

    @Test
    fun `pending enters after the stable time or drops on anything else`() {
        val pending = GateState.Pending(g)
        assertEquals(step(GateState.Pending(g2), 600L, null), TabletopGate.onInput(pending, TabletopResolution.Accepted(g2)))
        assertEquals(step(GateState.Off, null, null), TabletopGate.onInput(pending, flatSame))
        assertEquals(step(GateState.Off, null, null), TabletopGate.onInput(pending, TabletopResolution.NoFold))
        assertEquals(step(GateState.Off, null, null), TabletopGate.onInput(pending, TabletopResolution.NoFold))
        assertEquals(step(GateState.On(g), null, GateEvent.Entered(g)), TabletopGate.onTimeout(pending))
    }

    @Test
    fun `on keeps the same geometry and leaves at once on anything but a same-size flat`() {
        val on = GateState.On(g)
        assertEquals(step(on, null, null), TabletopGate.onInput(on, accepted))
        assertEquals(
            step(GateState.Pending(g2), 600L, GateEvent.Exited("geometry")),
            TabletopGate.onInput(on, TabletopResolution.Accepted(g2)),
        )
        assertEquals(step(GateState.Leaving(g), 300L, null), TabletopGate.onInput(on, flatSame))
        assertEquals(step(GateState.Off, null, GateEvent.Exited("geometry")), TabletopGate.onInput(on, flatOther))
        assertEquals(step(GateState.Off, null, GateEvent.Exited("noFold")), TabletopGate.onInput(on, TabletopResolution.NoFold))
        assertEquals(step(on, null, null), TabletopGate.onTimeout(on))
    }

    @Test
    fun `leaving returns, leaves at once, or leaves flat on timeout`() {
        val leaving = GateState.Leaving(g)
        assertEquals(step(GateState.On(g), null, null), TabletopGate.onInput(leaving, accepted))
        assertEquals(
            step(GateState.Pending(g2), 600L, GateEvent.Exited("geometry")),
            TabletopGate.onInput(leaving, TabletopResolution.Accepted(g2)),
        )
        assertEquals(step(GateState.Off, null, GateEvent.Exited("geometry")), TabletopGate.onInput(leaving, flatOther))
        assertEquals(step(GateState.Off, null, GateEvent.Exited("noFold")), TabletopGate.onInput(leaving, TabletopResolution.NoFold))
        assertEquals(step(GateState.Off, null, GateEvent.Exited("flat")), TabletopGate.onTimeout(leaving))
    }

    @Test
    fun `only on and leaving publish a geometry`() {
        assertNull(GateState.Off.geometry)
        assertNull(GateState.Pending(g).geometry)
        assertEquals(g, GateState.On(g).geometry)
        assertEquals(g, GateState.Leaving(g).geometry)
    }

    /** Feeds inputs; a timeout fires only when the time since the last restart reaches the step's timer. */
    private class Driver {
        var state: GateState = GateState.Off
        val events = mutableListOf<GateEvent>()
        private var timer: Long? = null
        private var elapsed = 0L
        var everOn = false

        fun input(r: TabletopResolution) {
            apply(TabletopGate.onInput(state, r))
        }

        fun advance(ms: Long) {
            var left = ms
            while (true) {
                val t = timer ?: return
                if (elapsed + left < t) {
                    elapsed += left
                    return
                }
                left -= t - elapsed
                apply(TabletopGate.onTimeout(state))
            }
        }

        private fun apply(step: GateStep) {
            state = step.state
            step.event?.let { events += it }
            timer = step.timerMs
            elapsed = 0L
            if (state is GateState.On) everOn = true
        }
    }

    @Test
    fun `xiaomi opening transient never enters`() {
        val d = Driver()
        d.input(TabletopResolution.Accepted(otherWindow))
        d.advance(340)
        d.input(TabletopResolution.Flat(1672, 2364))
        d.advance(2_000)
        assertEquals(false, d.everOn)
        assertEquals(GateState.Off, d.state)
    }

    @Test
    fun `closing exits on the frame features disappear`() {
        val d = Driver()
        d.input(accepted)
        d.advance(599)
        assertEquals(GateState.Pending(g), d.state)
        d.advance(1)
        assertEquals(GateState.On(g), d.state)
        d.input(TabletopResolution.NoFold)
        assertEquals(GateState.Off, d.state)
        assertEquals(listOf(GateEvent.Entered(g), GateEvent.Exited("noFold")), d.events)
    }

    @Test
    fun `a flat flicker shorter than the hysteresis stays on`() {
        val d = Driver()
        d.input(accepted)
        d.advance(600)
        d.input(flatSame)
        d.advance(200)
        d.input(accepted)
        d.advance(1_000)
        assertEquals(GateState.On(g), d.state)
        assertEquals(listOf<GateEvent>(GateEvent.Entered(g)), d.events)
    }

    @Test
    fun `a resize while flat exits at once without leaving`() {
        val d = Driver()
        d.input(accepted)
        d.advance(600)
        d.input(flatOther)
        assertEquals(GateState.Off, d.state)
        assertEquals(GateEvent.Exited("geometry"), d.events.last())
    }

    @Test
    fun `leaving then a resized flat exits at once`() {
        val d = Driver()
        d.input(accepted)
        d.advance(600)
        d.input(flatSame)
        assertEquals(GateState.Leaving(g), d.state)
        d.input(flatOther)
        assertEquals(GateState.Off, d.state)
        assertEquals(GateEvent.Exited("geometry"), d.events.last())
    }

    @Test
    fun `a new window size restarts the wait and logs the exit`() {
        val d = Driver()
        d.input(accepted)
        d.advance(600)
        d.input(TabletopResolution.Accepted(otherWindow))
        assertEquals(GateState.Pending(otherWindow), d.state)
        assertEquals(GateEvent.Exited("geometry"), d.events.last())
        d.advance(600)
        assertEquals(GateState.On(otherWindow), d.state)
    }

    @Test
    fun `enter line carries the field numbers`() {
        val xiaomi = (
            resolveTabletop(
                CallFoldReport(listOf(CallFold(CallFoldState.HALF_OPENED, true, false, PxRect(0, 1182, 1672, 1182)))),
                0, 0, 1672, 2364, 2.75f,
            ) as TabletopResolution.Accepted
            ).geometry
        assertEquals(
            "[Call] tabletop enter fold=horizontal bandDp=430-430 windowDp=608x860 mw=false",
            formatTabletopEnter(xiaomi, multiWindow = false),
        )
    }
}
