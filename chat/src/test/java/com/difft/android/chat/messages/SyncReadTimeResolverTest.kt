package com.difft.android.chat.messages

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the #1020 Phase 2 sync-read readTime decision
 * ([SyncReadTimeResolver.resolveSyncReadAt]). Pure function — no WCDB / native libs required.
 */
class SyncReadTimeResolverTest {

    // Plausible ms base (~2023-11, above the MIN_PLAUSIBLE_READ_AT_MS floor). Offsets keep the
    // original relative ordering (readAt vs envelope vs fallback) while staying above the floor.
    private val base = 1_700_000_000_000L

    // Normal: readAt present, plausible, and below the envelope upper bound → readAt is used verbatim.
    @Test
    fun `normal readAt below envelope is used`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = base + 2_000L,
            envelopeServerTimestamp = base + 3_000L,
            fallback = base + 1_000L
        )
        assertEquals(base + 2_000L, result)
    }

    // Old client: readAt == 0 → fall back to the early-biased message timestamp.
    @Test
    fun `zero readAt falls back to message timestamp`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = 0L,
            envelopeServerTimestamp = base + 3_000L,
            fallback = base + 1_000L
        )
        assertEquals(base + 1_000L, result)
    }

    // Old client: negative readAt is also treated as absent → fallback.
    @Test
    fun `negative readAt falls back to message timestamp`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = -5L,
            envelopeServerTimestamp = base + 3_000L,
            fallback = base + 1_000L
        )
        assertEquals(base + 1_000L, result)
    }

    // Sub-plausible readAt: a seconds-unit or garbage value below the plausibility floor → fallback.
    @Test
    fun `sub-plausible readAt below floor falls back to message timestamp`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = 1_700_000_000L, // seconds-unit (~2023-11 in seconds) → below the ms floor
            envelopeServerTimestamp = base + 3_000L,
            fallback = base + 1_000L
        )
        assertEquals(base + 1_000L, result)
    }

    // Tampered sender clock: readAt in the future is clamped by the receipt envelope's server time.
    @Test
    fun `future readAt is clamped by envelope server timestamp`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = base + 9_999_999L,
            envelopeServerTimestamp = base + 3_000L,
            fallback = base + 1_000L
        )
        assertEquals(base + 3_000L, result)
    }

    // Boundary: readAt exactly equal to the envelope bound → unchanged.
    @Test
    fun `readAt equal to envelope is unchanged`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = base + 3_000L,
            envelopeServerTimestamp = base + 3_000L,
            fallback = base + 1_000L
        )
        assertEquals(base + 3_000L, result)
    }

    // No server bound: readAt is valid but envelopeServerTimestamp <= 0 (systemShowTimestamp absent).
    // Without a genuine server upper bound the untrusted readAt must NOT be trusted → fallback.
    @Test
    fun `valid readAt with no server bound falls back to message timestamp`() {
        val result = SyncReadTimeResolver.resolveSyncReadAt(
            payloadReadAt = base + 2_000L,
            envelopeServerTimestamp = 0L,
            fallback = base + 1_000L
        )
        assertEquals(base + 1_000L, result)
    }
}
