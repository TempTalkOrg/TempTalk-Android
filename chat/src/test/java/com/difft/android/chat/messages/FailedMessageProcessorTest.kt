package com.difft.android.chat.messages

import io.mockk.mockk
import org.difft.app.database.WCDB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Pure-function tests for [FailedMessageProcessor].
 *
 * Covers the parts that DON'T need WCDB native libs (constants + backoff
 * jitter). The state-machine paths (`saveTransient` / `processOne` /
 * `loadDueMessages` / TTL cleanup) require a live WCDB and are deferred to
 * instrumentation tests when a device harness is available — same blocker
 * as the existing `GroupCryptoRepoTest`.
 */
class FailedMessageProcessorTest {

    private lateinit var processor: FailedMessageProcessor

    @Before
    fun setUp() {
        processor = FailedMessageProcessor(
            envelopToMessageProcessor = mockk(relaxed = true),
            wcdb = mockk<WCDB>(relaxed = true),
        )
    }

    // -- Constants ------------------------------------------------------------

    @Test
    fun `MAX_RETRIES equals 5`() {
        assertEquals(5, FailedMessageProcessor.MAX_RETRIES)
    }

    @Test
    fun `MAX_PER_TICK equals 50`() {
        assertEquals(50, FailedMessageProcessor.MAX_PER_TICK)
    }

    @Test
    fun `TTL_MILLIS equals 3 days`() {
        assertEquals(TimeUnit.DAYS.toMillis(3), FailedMessageProcessor.TTL_MILLIS)
    }

    @Test
    fun `BACKOFF_TABLE has 5 entries matching design (1s, 5s, 30s, 5m, 30m)`() {
        val expected = longArrayOf(
            TimeUnit.SECONDS.toMillis(1),
            TimeUnit.SECONDS.toMillis(5),
            TimeUnit.SECONDS.toMillis(30),
            TimeUnit.MINUTES.toMillis(5),
            TimeUnit.MINUTES.toMillis(30),
        )
        assertEquals(expected.size, FailedMessageProcessor.BACKOFF_TABLE.size)
        for (i in expected.indices) {
            assertEquals("BACKOFF_TABLE[$i]", expected[i], FailedMessageProcessor.BACKOFF_TABLE[i])
        }
    }

    // -- backoffMillis ---------------------------------------------------------

    @Test
    fun `backoffMillis(0) is 1s plus or minus 20 percent jitter`() {
        val base = TimeUnit.SECONDS.toMillis(1)
        val tolerance = base / 5L
        repeat(100) {
            val v = processor.backoffMillis(0)
            assertTrue(
                "backoffMillis(0)=$v should be in [${base - tolerance}, ${base + tolerance}]",
                v in (base - tolerance)..(base + tolerance)
            )
        }
    }

    @Test
    fun `backoffMillis(4) is 30 min plus or minus 20 percent jitter`() {
        val base = TimeUnit.MINUTES.toMillis(30)
        val tolerance = base / 5L
        repeat(100) {
            val v = processor.backoffMillis(4)
            assertTrue(
                "backoffMillis(4)=$v should be in [${base - tolerance}, ${base + tolerance}]",
                v in (base - tolerance)..(base + tolerance)
            )
        }
    }

    @Test
    fun `backoffMillis clamps retryCount above MAX to last bucket (defensive)`() {
        // retryCount > MAX_RETRIES shouldn't happen in practice — row is
        // deleted at give-up — but the clamp guards against caller bugs.
        val v = processor.backoffMillis(99)
        val base = TimeUnit.MINUTES.toMillis(30)
        val tolerance = base / 5L
        assertTrue(v in (base - tolerance)..(base + tolerance))
    }

    @Test
    fun `backoffMillis jitter is actually random (not constant)`() {
        // Sanity check: ±20% jitter should produce different values across
        // many calls. If two consecutive calls always return the same value,
        // jitter is broken.
        val samples = (0..50).map { processor.backoffMillis(2) }.toSet()
        assertNotEquals("Expected jitter variance across samples", 1, samples.size)
    }
}
