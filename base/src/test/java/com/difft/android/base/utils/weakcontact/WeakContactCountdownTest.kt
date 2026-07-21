package com.difft.android.base.utils.weakcontact

import android.os.SystemClock
import com.difft.android.base.utils.time.ServerTimeProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Unit tests for [WeakContactCountdown].
 *
 * - Pure [WeakContactCountdown.daysLeft] across serverNow/anchor/now/expireAt combinations
 *   (1.5 days left / 12h left / already expired / +1h monotonic extrapolation), each ceil with floor 1.
 * - Framework assumption: [SystemClock.elapsedRealtime] monotonicity. After anchoring, advancing the
 *   real Robolectric elapsedRealtime makes the extrapolated "now" advance (remaining decreases).
 *
 * Robolectric runner is used only so `SystemClock` is available to the monotonicity test; the
 * daysLeft tests are pure arithmetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WeakContactCountdownTest {

    private val dayMs = 24L * 60 * 60 * 1000

    @After
    fun tearDown() {
        // ServerTimeProvider is a process-singleton; unanchor so sibling tests see a clean state.
        clearClockAnchor()
    }

    // ---- pure daysLeft combinations -----------------------------------------------------

    @Test
    fun `T1 daysLeft 1_5 days remaining rounds up to 2`() {
        // anchor == now (no extrapolation); 1.5 days remaining → ceil = 2.
        val serverNow = 1_000_000_000_000L
        val expireAt = serverNow + (dayMs * 3 / 2) // +1.5 days
        val result = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = 500L,
            nowElapsed = 500L,
            expireAt = expireAt,
        )
        assertEquals(2, result)
    }

    @Test
    fun `T1 daysLeft 12h remaining floors at 1`() {
        // 0.5 day remaining → ceil = 1 (also the floor).
        val serverNow = 1_000_000_000_000L
        val expireAt = serverNow + (dayMs / 2) // +12h
        val result = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = 0L,
            nowElapsed = 0L,
            expireAt = expireAt,
        )
        assertEquals(1, result)
    }

    @Test
    fun `T1 daysLeft already expired floors at 1`() {
        // expireAt < effectiveNow → remaining <= 0 → floor 1 (delete is server-driven, never 0).
        val serverNow = 1_000_000_000_000L
        val expireAt = serverNow - dayMs // expired a day ago
        val result = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = 0L,
            nowElapsed = 0L,
            expireAt = expireAt,
        )
        assertEquals(1, result)
    }

    @Test
    fun `T1 daysLeft monotonic extrapolation by 1h still rounds up`() {
        // Anchor at serverNow; 1h of real monotonic time elapsed after the anchor.
        // expireAt = serverNow + 2 days + 1h. effectiveNow = serverNow + 1h.
        // remaining = 2 days exactly → ceil = 2.
        val serverNow = 1_000_000_000_000L
        val oneHour = 60L * 60 * 1000
        val expireAt = serverNow + (dayMs * 2) + oneHour
        val result = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = 10_000L,
            nowElapsed = 10_000L + oneHour, // +1h monotonic
            expireAt = expireAt,
        )
        assertEquals(2, result)
    }

    @Test
    fun `T1 daysLeft extrapolation consumes remaining time`() {
        // Same anchor/expire but more elapsed time → fewer days remaining.
        val serverNow = 1_000_000_000_000L
        val expireAt = serverNow + (dayMs * 3) // 3 days from anchor
        // 1 full day of monotonic time elapsed since anchor → 2 days remaining → ceil 2.
        val result = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = 0L,
            nowElapsed = dayMs, // +1 day monotonic
            expireAt = expireAt,
        )
        assertEquals(2, result)
    }

    // ---- SystemClock.elapsedRealtime monotonicity assumption ----------------------------

    @Test
    fun `T12 daysLeft tracks real SystemClock elapsedRealtime advancing`() {
        // Verify the helper's assumption against the REAL SystemClock used by daysLeftFromClock:
        // anchor at the current elapsedRealtime, then read again later — the gap is non-negative
        // (monotonic) and is correctly subtracted from the remaining window.
        val anchorElapsed = SystemClock.elapsedRealtime()
        val serverNow = 2_000_000_000_000L
        val expireAt = serverNow + (dayMs * 5)

        val before = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = anchorElapsed,
            nowElapsed = SystemClock.elapsedRealtime(),
            expireAt = expireAt,
        )

        // Advance the Robolectric monotonic clock by 2 full days.
        SystemClock.setCurrentTimeMillis(System.currentTimeMillis()) // touch shadow
        val laterElapsed = anchorElapsed + (dayMs * 2)
        val after = WeakContactCountdown.daysLeft(
            serverNow = serverNow,
            anchorElapsed = anchorElapsed,
            nowElapsed = laterElapsed,
            expireAt = expireAt,
        )

        // 5 days → 3 days after 2 days of monotonic time elapsed; strictly decreasing.
        assertEquals(5, before)
        assertEquals(3, after)
    }

    // ---- daysLeftFromClock unanchored fallback (routes through ServerTimeProvider.nowMillis) --------

    @Test
    fun `daysLeftFromClock uses ServerTimeProvider nowMillis when unanchored`() {
        // No L1 anchor: nowMillis falls through to L2/L3 (wall + offset). Inject a deterministic wall
        // clock so the countdown is exact. expireAt 3 days out (minus 1ms) → ceil = 3.
        val now = 1_700_000_000_000L
        ServerTimeProvider.resetForTest(wallClock = { now }, elapsedClock = { 0L })
        val expireAt = now + (dayMs * 3) - 1
        val result = WeakContactCountdown.daysLeftFromClock(expireAt)
        assertEquals(3, result)
    }

    /** Reset ServerTimeProvider to an unanchored state so sibling tests see a clean process-singleton. */
    private fun clearClockAnchor() {
        ServerTimeProvider.resetForTest(wallClock = { 0L }, elapsedClock = { 0L })
    }
}
