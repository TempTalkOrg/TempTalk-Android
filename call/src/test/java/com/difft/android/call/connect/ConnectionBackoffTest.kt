package com.difft.android.call.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the retry loop's own bounds. Classification decides WHETHER to retry; these constants
 * decide how long retrying is allowed to take, which is the half that has no other guard —
 * nothing outside the coordinator caps the connect phase.
 */
class ConnectionBackoffTest {

    @Test
    fun `first failure retries immediately and early retries escalate`() {
        assertEquals(0L, ConnectionBackoff.delayMsBeforeRetryAfterFailure(0))
        assertEquals(0L, ConnectionBackoff.delayMsBeforeRetryAfterFailure(1))
        assertEquals(500L, ConnectionBackoff.delayMsBeforeRetryAfterFailure(2))
        assertEquals(1_000L, ConnectionBackoff.delayMsBeforeRetryAfterFailure(3))
        assertEquals(2_000L, ConnectionBackoff.delayMsBeforeRetryAfterFailure(4))
        assertEquals(5_000L, ConnectionBackoff.delayMsBeforeRetryAfterFailure(5))
    }

    @Test
    fun `delay never exceeds the ceiling however many failures accumulate`() {
        (6..100).forEach { n ->
            val delay = ConnectionBackoff.delayMsBeforeRetryAfterFailure(n)
            assertTrue("n=$n delay=$delay", delay in 0..30_000L)
        }
        assertEquals(30_000L, ConnectionBackoff.delayMsBeforeRetryAfterFailure(100))
    }

    @Test
    fun `negative failure count cannot produce a delay`() {
        assertEquals(0L, ConnectionBackoff.delayMsBeforeRetryAfterFailure(-1))
    }

    @Test
    fun `the wall-clock budget is what bounds the wait, not the failure cap`() {
        // Documents why CONNECT_BUDGET_MS exists: exhausting the failure cap alone schedules
        // minutes of backoff, so the failure count cannot be the effective ceiling.
        val backoffOnlyMs = (1..CallConnectionCoordinator.MAX_TRANSIENT_FAILURES)
            .sumOf { ConnectionBackoff.delayMsBeforeRetryAfterFailure(it) }
        assertTrue(
            "backoff-only wait $backoffOnlyMs ms should dwarf the budget ${CallConnectionCoordinator.CONNECT_BUDGET_MS} ms",
            backoffOnlyMs > CallConnectionCoordinator.CONNECT_BUDGET_MS * 3,
        )
    }
}
