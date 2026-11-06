package com.difft.android.network.config

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RefreshGate]: due on the first check, then once the interval has elapsed on its monotonic
 * clock. A wall-clock step back (the old `System.currentTimeMillis()` throttle stalled until the
 * wall clock caught up) cannot delay it, because the gate never reads the wall clock (#1395).
 */
class RefreshGateTest {

    private var monoNow = 50_000L
    private val gate = RefreshGate { monoNow }

    @Test
    fun `the first check is due and reports no previous refresh`() {
        assertTrue(gate.isDue(INTERVAL))
        assertNull(gate.sinceLastMs())
    }

    @Test
    fun `a stamped gate is due once the interval elapses on the monotonic clock`() {
        gate.markRefreshed()
        monoNow += INTERVAL - 1
        assertFalse(gate.isDue(INTERVAL))

        monoNow += 1
        assertTrue(gate.isDue(INTERVAL))
        assertEquals(INTERVAL, gate.sinceLastMs())
    }

    @Test
    fun `the stamp is a monotonic reading, not wall time`() {
        monoNow = 7_000L
        gate.markRefreshed()

        assertEquals(7_000L, gate.lastRefreshTime)
        assertTrue(gate.lastRefreshTime < DAY_MS, "far below any epoch-millis value")
    }

    private companion object {
        const val INTERVAL = 5 * 60 * 1000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
