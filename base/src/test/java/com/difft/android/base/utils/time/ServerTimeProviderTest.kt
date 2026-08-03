package com.difft.android.base.utils.time

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ServerTimeProvider] (pure JUnit — clocks injected via [ServerTimeProvider.resetForTest]).
 *
 * Covers design-android §6 plus the round-4 guards:
 * - anchor rebuild arithmetic (L1: serverNow + monotonic delta);
 * - update guards (`serverNow <= 0` ignored; implausible/seconds-unit rejected; backward-stale rejected;
 *   backward-within-tolerance and forward jumps accepted);
 * - `lastKnownServerTime` advances on EVERY accepted update (not only when the offset delta > 1s);
 * - cold-start clamp `max(wall + offset, lastKnownServerTime)` (L2), both branches;
 * - persistence throttle (offset persisted only when |Δoffset| > 1s within the lastKnown window);
 * - wall-clock rollback does not affect an anchored reading;
 * - dataStore-null (persistence-disabled) path stays crash-free.
 *
 * All `serverNow` values fed to [ServerTimeProvider.update] sit inside the plausibility window
 * (~2020-09 .. 2100); values below it now model unit-confusion and are rejected.
 */
class ServerTimeProviderTest {

    // Mutable fake clocks; the injected lambdas read these so tests can advance time deterministically.
    private var fakeWall = 0L
    private var fakeElapsed = 0L

    private fun reset(persistedOffset: Long = 0L, lastKnownServerTime: Long = 0L) {
        ServerTimeProvider.resetForTest(
            wallClock = { fakeWall },
            elapsedClock = { fakeElapsed },
            persistedOffset = persistedOffset,
            lastKnownServerTime = lastKnownServerTime,
        )
    }

    @Test
    fun `anchor rebuild uses monotonic delta`() {
        reset()
        fakeWall = BASE
        fakeElapsed = 1_000L
        ServerTimeProvider.update(serverNow = BASE, source = "test")

        assertTrue(ServerTimeProvider.isAnchored())
        assertEquals(BASE, ServerTimeProvider.nowMillis(), "at anchor instant now == serverNow")

        fakeElapsed = 6_000L // 5s of monotonic time passed
        assertEquals(BASE + 5_000L, ServerTimeProvider.nowMillis(), "now advances by the elapsed delta")
    }

    @Test
    fun `update with non-positive serverNow is ignored`() {
        reset()
        fakeElapsed = 1_000L
        ServerTimeProvider.update(serverNow = BASE, source = "test")
        val anchored = ServerTimeProvider.nowMillis()

        ServerTimeProvider.update(serverNow = 0L, source = "test")
        ServerTimeProvider.update(serverNow = -5L, source = "test")

        assertEquals(anchored, ServerTimeProvider.nowMillis(), "invalid serverNow must not clobber anchor")
    }

    @Test
    fun `non-positive serverNow does not create an anchor`() {
        reset()
        ServerTimeProvider.update(serverNow = 0L, source = "test")
        ServerTimeProvider.update(serverNow = -100L, source = "test")

        assertFalse(ServerTimeProvider.isAnchored(), "invalid serverNow must not anchor")
    }

    @Test
    fun `seconds-unit value never anchors`() {
        reset()
        // ~1.7e9 is a seconds-scale epoch (below the ms plausibility floor) → rejected.
        ServerTimeProvider.update(serverNow = 1_700_000_000L, source = "test")
        assertFalse(ServerTimeProvider.isAnchored(), "seconds-unit value must be rejected")
    }

    @Test
    fun `seconds-unit value does not disturb an existing anchor`() {
        reset()
        fakeElapsed = 1_000L
        ServerTimeProvider.update(serverNow = BASE, source = "test")
        val before = ServerTimeProvider.nowMillis()

        ServerTimeProvider.update(serverNow = 1_700_000_000L, source = "test") // seconds-scale poison
        assertEquals(before, ServerTimeProvider.nowMillis(), "implausible value must not re-anchor")
    }

    @Test
    fun `backward serverNow beyond tolerance is rejected when anchored`() {
        reset()
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = BASE, source = "test")

        ServerTimeProvider.update(serverNow = BASE - 60_000L, source = "test") // 60s back > 30s tolerance
        assertEquals(BASE, ServerTimeProvider.nowMillis(), "stale/out-of-order source must not rewind the clock")
    }

    @Test
    fun `backward serverNow within tolerance is accepted`() {
        reset()
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = BASE, source = "test")

        val slightlyBack = BASE - 10_000L // within the 30s jitter tolerance
        ServerTimeProvider.update(serverNow = slightlyBack, source = "test")
        assertEquals(slightlyBack, ServerTimeProvider.nowMillis(), "small backward jitter re-anchors normally")
    }

    @Test
    fun `large forward jump still rebuilds the anchor`() {
        reset()
        fakeElapsed = 1_000L
        ServerTimeProvider.update(serverNow = BASE, source = "test")

        val jumped = BASE + 25L * 60 * 60 * 1000 // +25h > 24h threshold (server authoritative)
        ServerTimeProvider.update(serverNow = jumped, source = "test")

        assertEquals(jumped, ServerTimeProvider.nowMillis(), "forward jump warns but still anchors to the new time")
    }

    @Test
    fun `lastKnownServerTime advances on every accepted update`() {
        reset()
        fakeWall = BASE
        fakeElapsed = 0L
        ServerTimeProvider.update(serverNow = BASE + 100L, source = "test") // first anchor → persist, offset=100
        assertEquals(100L, ServerTimeProvider.persistedOffsetForTest())
        assertEquals(BASE + 100L, ServerTimeProvider.lastKnownServerTimeForTest())

        // +200ms offset move (< 1s, < 30min) → NOT persisted, but lastKnown must still advance in-memory.
        ServerTimeProvider.update(serverNow = BASE + 300L, source = "test")
        assertEquals(100L, ServerTimeProvider.persistedOffsetForTest(), "sub-second offset move must not persist")
        assertEquals(BASE + 300L, ServerTimeProvider.lastKnownServerTimeForTest(), "lastKnown advances every update")
    }

    @Test
    fun `cold-start clamp returns lastKnown when wall plus offset is earlier`() {
        // No anchor; persisted offset small, but lastKnownServerTime is far ahead → clamp wins.
        reset(persistedOffset = 100L, lastKnownServerTime = 5_000_000L)
        fakeWall = 1_000_000L

        assertFalse(ServerTimeProvider.isAnchored())
        assertEquals(5_000_000L, ServerTimeProvider.nowMillis(), "rollback clamped to lastKnownServerTime")
    }

    @Test
    fun `cold-start clamp returns wall plus offset when it is later`() {
        reset(persistedOffset = 100L, lastKnownServerTime = 500_000L)
        fakeWall = 1_000_000L

        assertEquals(1_000_100L, ServerTimeProvider.nowMillis(), "wall+offset used when ahead of lastKnown")
    }

    @Test
    fun `L3 bare wall clock when nothing anchored or persisted`() {
        reset() // offset=0, lastKnown=0
        fakeWall = 1_700_000_000_000L

        assertEquals(1_700_000_000_000L, ServerTimeProvider.nowMillis(), "collapses to bare wall clock")
    }

    @Test
    fun `persistence is throttled on sub-second offset changes`() {
        reset()
        fakeWall = BASE
        ServerTimeProvider.update(serverNow = BASE + 500L, source = "test") // first anchor → persist, offset=500
        assertEquals(500L, ServerTimeProvider.persistedOffsetForTest())

        // offset delta 300ms (< 1s), lastKnown moved only 300ms (< 30min) → not persisted
        ServerTimeProvider.update(serverNow = BASE + 800L, source = "test")
        assertEquals(500L, ServerTimeProvider.persistedOffsetForTest(), "small offset change must not persist")

        // offset delta 1500ms (> 1s) → persisted
        ServerTimeProvider.update(serverNow = BASE + 2_000L, source = "test")
        assertEquals(2_000L, ServerTimeProvider.persistedOffsetForTest(), "large offset change persists")
        assertEquals(BASE + 2_000L, ServerTimeProvider.lastKnownServerTimeForTest())
    }

    @Test
    fun `anchored reading is immune to wall-clock rollback`() {
        reset()
        fakeWall = BASE
        fakeElapsed = 1_000L
        ServerTimeProvider.update(serverNow = BASE, source = "test")

        fakeWall = 0L // user rolls the wall clock back; monotonic clock unchanged
        assertEquals(BASE, ServerTimeProvider.nowMillis(), "L1 anchor ignores wall-clock changes")
    }

    @Test
    fun `update and read stay crash-free when persistence is disabled (dataStore null path)`() {
        // resetForTest disables persistence and never resolves the DataStore — the whole flow must be safe.
        reset()
        fakeWall = BASE
        fakeElapsed = 2_000L
        ServerTimeProvider.update(serverNow = BASE, source = "test")

        fakeElapsed = 5_000L
        assertEquals(BASE + 3_000L, ServerTimeProvider.nowMillis(), "no crash without a backing DataStore")
    }

    private companion object {
        /** Plausible epoch-ms base (~2023-11) inside the [ServerTimeProvider] plausibility window. */
        private const val BASE = 1_700_000_000_000L
    }
}
