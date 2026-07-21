package com.difft.android.chat.messages

/** readTime to persist for an incoming sync-read. Pure (no WCDB) for unit-testability. (#1020 Phase 2) */
object SyncReadTimeResolver {

    // Lower plausibility floor (mirrors ServerTimeProvider.MIN_PLAUSIBLE_MS, ~2020-09): a seconds-unit
    // or garbage payloadReadAt falls below it and is rejected.
    private const val MIN_PLAUSIBLE_READ_AT_MS = 1_600_000_000_000L

    /**
     * Trusts [payloadReadAt] (sender's ReadPosition.readAt) only when it is plausible (>= the
     * [MIN_PLAUSIBLE_READ_AT_MS] floor, rejecting seconds-unit/garbage values) AND clamped by a real
     * server bound [envelopeServerTimestamp] (the receipt's systemShowTimestamp — reading precedes the
     * receipt, so it caps a tampered future readAt). Otherwise -> [fallback] (early-biased; readTime is
     * only ever set earlier, never extended). The bound MUST be server-assigned — never substitute the
     * client-echoed envelope.timestamp.
     */
    fun resolveSyncReadAt(payloadReadAt: Long, envelopeServerTimestamp: Long, fallback: Long): Long =
        if (payloadReadAt < MIN_PLAUSIBLE_READ_AT_MS || envelopeServerTimestamp <= 0L) fallback
        else minOf(payloadReadAt, envelopeServerTimestamp)
}
