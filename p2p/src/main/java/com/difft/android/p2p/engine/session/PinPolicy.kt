package com.difft.android.p2p.engine.session

import java.security.SecureRandom

/**
 * One-time, time-limited 6-digit PIN for the QR pairing handshake. Generated when the receiver shows
 * its QR, consumed on the first successful `prepare-upload`, and expired after [ttlMillis].
 *
 * The PIN space is only 10^6 and `prepare-upload` is not throttled, so wrong guesses are capped at
 * [MAX_PIN_ATTEMPTS] per issued PIN, bounding an attacker's odds at 5/10^6. Hitting the cap
 * invalidates the PIN rather than backing off — a sleep would pin the connection thread and hand the
 * attacker a resource-exhaustion surface. The residual is a visible DoS: the user re-shows the QR.
 *
 * Thread-safety: [validateAndConsume] and [reset] are `synchronized`, so the consume check has no
 * check-then-act race between concurrent prepare-upload connections.
 *
 * @param clock injectable time source (millis) for deterministic TTL tests.
 */
class PinPolicy(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()

    @Volatile private var pin: String? = null
    @Volatile private var issuedAt: Long = 0L
    @Volatile private var consumed: Boolean = false

    /** Wrong guesses against the currently issued PIN. Reset by [issue] / [reset], capped by [MAX_PIN_ATTEMPTS]. */
    private var failedAttempts: Int = 0

    /** Generates a fresh 6-digit PIN, resetting any prior state. Returns the new PIN. */
    fun issue(): String = synchronized(lock) {
        val value = "%06d".format(random.nextInt(1_000_000))
        pin = value
        issuedAt = clock()
        consumed = false
        failedAttempts = 0
        value
    }

    /** The currently active PIN, or null if none issued. (For QR display only.) */
    fun current(): String? = pin

    /**
     * Validates [candidate] against the active PIN. On success marks it consumed (one-time).
     * Returns false if no PIN issued, expired, already consumed, mismatched, or if the PIN was
     * already invalidated by hitting [MAX_PIN_ATTEMPTS] wrong guesses.
     *
     * Only a mismatch against a live PIN counts as an attempt. An expired PIN clears state
     * without counting, so a stale PIN can't be used as a free probe counter, and the
     * no-PIN / already-consumed paths have nothing left to brute-force.
     */
    fun validateAndConsume(candidate: String?): Boolean = synchronized(lock) {
        if (!liveMatch(candidate)) return false
        consumed = true
        true
    }

    /**
     * Checks [candidate] against the live PIN without consuming it — the claim a scanner makes the
     * moment it reads the code, before its user has confirmed anything. A mismatch counts as an
     * attempt exactly as in [validateAndConsume], so the brute-force cap is shared.
     */
    fun matches(candidate: String?): Boolean = synchronized(lock) { liveMatch(candidate) }

    /** Restarts the live PIN's clock: a claimed code stays valid while its holder reads the confirmation page. */
    fun touch() = synchronized(lock) { if (pin != null) issuedAt = clock() }

    /** Caller must hold [lock]. */
    private fun liveMatch(candidate: String?): Boolean {
        val active = pin ?: return false
        if (consumed) return false
        if (clock() - issuedAt > ttlMillis) {
            clearState()
            return false
        }
        if (candidate == null || !constantTimeEquals(candidate, active)) {
            failedAttempts++
            // Lock out by invalidating the PIN entirely: every later call now falls out at the
            // `pin ?: return false` above, so a locked-out attacker sees the same uniform false.
            if (failedAttempts >= MAX_PIN_ATTEMPTS) clearState()
            return false
        }
        return true
    }

    /** Clears PIN state (receiver stopped / session ended). */
    fun reset() = synchronized(lock) { clearState() }

    /** Caller must hold [lock]. */
    private fun clearState() {
        pin = null
        consumed = false
        issuedAt = 0L
        failedAttempts = 0
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var result = 0
        for (i in x.indices) result = result or (x[i].toInt() xor y[i].toInt())
        return result == 0
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 5 * 60 * 1000L // 5 minutes

        /** Wrong guesses allowed per issued PIN before it is invalidated. See class KDoc. */
        const val MAX_PIN_ATTEMPTS: Int = 5
    }
}
