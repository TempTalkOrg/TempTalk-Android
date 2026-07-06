package com.difft.android.base.utils.weakcontact

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [WeakContactClock].
 *
 * The clock is a process-level in-memory anchor singleton (no Android deps → pure JUnit).
 * Covers:
 * - first [WeakContactClock.update] writes the anchor (serverNow, elapsed);
 * - `serverNow <= 0` does NOT overwrite a good anchor (dirty-anchor guard);
 * - [WeakContactClock.snapshot] returns null before any valid update.
 *
 * The "null anchor → daysLeftFromClock falls back to wall clock" leg lives in
 * [WeakContactCountdownTest] since it exercises [WeakContactCountdown].
 */
class WeakContactClockTest {

    @Before
    fun reset() {
        // Reset the singleton's anchor to null for test isolation (no public clear API).
        val field = WeakContactClock::class.java.getDeclaredField("anchor")
        field.isAccessible = true
        field.set(WeakContactClock, null)
    }

    @Test
    fun `T15 update writes anchor`() {
        WeakContactClock.update(serverNow = 1000L, elapsedRealtime = 500L)

        val anchor = WeakContactClock.snapshot()
        assertNotNull(anchor)
        assertEquals(1000L, anchor.serverNow)
        assertEquals(500L, anchor.anchorElapsed)
    }

    @Test
    fun `T15 snapshot is null before any valid update`() {
        assertNull(WeakContactClock.snapshot())
    }

    @Test
    fun `T15 update with serverNow zero does not overwrite good anchor`() {
        WeakContactClock.update(serverNow = 1000L, elapsedRealtime = 500L)
        WeakContactClock.update(serverNow = 0L, elapsedRealtime = 999L) // invalid → ignored

        val anchor = WeakContactClock.snapshot()
        assertNotNull(anchor)
        assertEquals(1000L, anchor.serverNow, "serverNow<=0 must not overwrite")
        assertEquals(500L, anchor.anchorElapsed)
    }

    @Test
    fun `T15 update with negative serverNow does not write when no anchor`() {
        WeakContactClock.update(serverNow = -5L, elapsedRealtime = 100L)
        assertNull(WeakContactClock.snapshot(), "negative serverNow must not create an anchor")
    }

    @Test
    fun `T15 later valid update overwrites earlier anchor (last-write-wins)`() {
        WeakContactClock.update(serverNow = 1000L, elapsedRealtime = 500L)
        WeakContactClock.update(serverNow = 2000L, elapsedRealtime = 1500L)

        val anchor = WeakContactClock.snapshot()
        assertNotNull(anchor)
        assertEquals(2000L, anchor.serverNow)
        assertEquals(1500L, anchor.anchorElapsed)
    }
}
