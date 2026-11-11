package com.difft.android.base.monitor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValidatedNetworkTrackerTest {

    private var elapsed = 1_000L
    private val tracker = ValidatedNetworkTracker { elapsed }
    private val hour = 60 * 60_000L

    @Test
    fun `a network already online when tracking starts counts from the first reading`() {
        assertNull(tracker.onlineMsWithin(hour))
        tracker.onValidity(1, true)
        elapsed += 5_000L
        assertEquals(5_000L, tracker.onlineMsWithin(hour))
        
        assertEquals(2_000L, tracker.onlineMsWithin(2_000L))
    }

    @Test
    fun `going offline closes the span and online time before the gap still counts`() {
        tracker.onValidity(1, true)
        elapsed += 6 * 60_000L
        tracker.onValidity(1, false)
        assertEquals(6 * 60_000L, tracker.onlineMsWithin(hour))
        elapsed += 60_000L
        tracker.onValidity(1, true)
        elapsed += 60_000L
        
        assertEquals(7 * 60_000L, tracker.onlineMsWithin(8 * 60_000L))
    }

    @Test
    fun `losing the default network is offline and a replacement starts a new span`() {
        tracker.onValidity(1, true)
        elapsed += 2_000L
        tracker.onLost(1)
        elapsed += 10_000L
        assertEquals(2_000L, tracker.onlineMsWithin(hour))
        tracker.onValidity(2, true)
        elapsed += 3_000L
        assertEquals(5_000L, tracker.onlineMsWithin(hour))
        assertEquals(3_000L, tracker.onlineMsWithin(3_000L))
    }

    @Test
    fun `a validated replacement network continues the span and losing the old one changes nothing`() {
        tracker.onValidity(1, true)
        elapsed += 4_000L
        tracker.onValidity(2, true)
        elapsed += 4_000L
        tracker.onLost(1)
        assertEquals(8_000L, tracker.onlineMsWithin(hour))
    }

    @Test
    fun `repeated capability callbacks do not restart the span`() {
        tracker.onValidity(1, false)
        tracker.onValidity(1, true)
        elapsed += 2_000L
        tracker.onValidity(1, true)
        elapsed += 2_000L
        assertEquals(4_000L, tracker.onlineMsWithin(hour))
    }

    @Test
    fun `spans older than a day are dropped`() {
        tracker.onValidity(1, true)
        elapsed += 1_000L
        tracker.onValidity(1, false)
        elapsed += ValidatedNetworkTracker.HISTORY_MS + 1
        tracker.onValidity(1, true)
        elapsed += 500L
        tracker.onValidity(1, false)
        assertEquals(500L, tracker.onlineMsWithin(2 * ValidatedNetworkTracker.HISTORY_MS))
    }
}
