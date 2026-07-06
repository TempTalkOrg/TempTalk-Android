package com.difft.android.chat.crypto

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pure host-JVM unit tests for [decideRGroupWrite] — the WCDB-free version-gate
 * for R_group writes. No native WCDB, not @Ignore'd: this is the executable
 * coverage for the version-decision logic (the @Ignore'd WCDB-integration stubs
 * TV1–TV6 in [GroupCryptoRepoTest] are instrumentation-only).
 *
 * Decision contract:
 * - stored == null                  → INSERT
 * - incoming  >  stored             → OVERWRITE
 * - incoming  == stored             → SKIP
 * - incoming  <  stored             → SKIP
 * - incoming  <  0 (uint32 overflow)→ SKIP (guard runs before any compare)
 */
class GroupCryptoRGroupDecisionTest {

    @Test
    fun `null stored version inserts`() {
        assertEquals(RGroupWriteDecision.INSERT, decideRGroupWrite(storedVersion = null, incomingVersion = 3))
    }

    @Test
    fun `incoming greater than stored overwrites`() {
        assertEquals(RGroupWriteDecision.OVERWRITE, decideRGroupWrite(storedVersion = 1, incomingVersion = 2))
    }

    @Test
    fun `incoming equal to stored skips`() {
        assertEquals(RGroupWriteDecision.SKIP, decideRGroupWrite(storedVersion = 2, incomingVersion = 2))
    }

    @Test
    fun `incoming lower than stored skips`() {
        assertEquals(RGroupWriteDecision.SKIP, decideRGroupWrite(storedVersion = 5, incomingVersion = 3))
    }

    @Test
    fun `stored zero incoming one overwrites`() {
        // v0 = original/un-rotated baseline; first rotation to v1 must overwrite + reset.
        assertEquals(RGroupWriteDecision.OVERWRITE, decideRGroupWrite(storedVersion = 0, incomingVersion = 1))
    }

    @Test
    fun `stored zero incoming zero skips`() {
        // saveRGroupIfNeeded idempotency: 0 > 0 is false → existing v0 row is left alone.
        assertEquals(RGroupWriteDecision.SKIP, decideRGroupWrite(storedVersion = 0, incomingVersion = 0))
    }

    @Test
    fun `negative incoming version skips`() {
        // uint32 >= 2^31 parsed into signed Int reads negative — guard must SKIP,
        // even when there is no stored row (guard runs before the null check).
        assertEquals(RGroupWriteDecision.SKIP, decideRGroupWrite(storedVersion = null, incomingVersion = -1))
        assertEquals(RGroupWriteDecision.SKIP, decideRGroupWrite(storedVersion = 5, incomingVersion = -1))
        assertEquals(RGroupWriteDecision.SKIP, decideRGroupWrite(storedVersion = null, incomingVersion = Int.MIN_VALUE))
    }

    @Test
    fun `large positive incoming version overwrites`() {
        assertEquals(RGroupWriteDecision.OVERWRITE, decideRGroupWrite(storedVersion = 5, incomingVersion = Int.MAX_VALUE))
    }
}
