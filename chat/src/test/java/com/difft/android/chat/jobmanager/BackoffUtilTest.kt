package com.difft.android.chat.jobmanager

import com.difft.android.chat.jobmanager.impl.BackoffUtil
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackoffUtilTest {

    @Test
    fun `attempt 1 with maxBackoff 60000 returns value in expected range`() {
        // 2^1 * 1000 = 2000, jitter 0.75-1.25 → [1500, 2500]
        repeat(100) {
            val result = BackoffUtil.exponentialBackoff(1, 60_000L)
            assertTrue(
                result in 1500..2500,
                "Expected result in [1500, 2500] but got $result"
            )
        }
    }

    @Test
    fun `attempt 2 with maxBackoff 60000 returns value in expected range`() {
        // 2^2 * 1000 = 4000, jitter 0.75-1.25 → [3000, 5000]
        repeat(100) {
            val result = BackoffUtil.exponentialBackoff(2, 60_000L)
            assertTrue(
                result in 3000..5000,
                "Expected result in [3000, 5000] but got $result"
            )
        }
    }

    @Test
    fun `attempt 5 with maxBackoff 60000 returns value in expected range`() {
        // 2^5 * 1000 = 32000, jitter 0.75-1.25 → [24000, 40000]
        repeat(100) {
            val result = BackoffUtil.exponentialBackoff(5, 60_000L)
            assertTrue(
                result in 24000..40000,
                "Expected result in [24000, 40000] but got $result"
            )
        }
    }

    @Test
    fun `attempt 10 is capped at maxBackoff with jitter`() {
        // 2^10 * 1000 = 1024000, capped to 60000, jitter 0.75-1.25 → [45000, 75000]
        // But since actualBackoff is capped at maxBackoff, upper bound is 60000 * 1.25 = 75000
        repeat(100) {
            val result = BackoffUtil.exponentialBackoff(10, 60_000L)
            assertTrue(
                result in 45000..75000,
                "Expected result in [45000, 75000] but got $result"
            )
        }
    }

    @Test
    fun `very large attempt count does not overflow`() {
        // pastAttemptCount is coerced to 30, so 2^30 * 1000 = very large, capped at maxBackoff
        repeat(100) {
            val result = BackoffUtil.exponentialBackoff(100, 60_000L)
            assertTrue(
                result in 45000..75000,
                "Expected capped result but got $result"
            )
        }
    }

    @Test
    fun `different maxBackoff values respected`() {
        // attempt 1, maxBackoff = 5000: 2^1 * 1000 = 2000, jitter → [1500, 2500]
        repeat(50) {
            val result = BackoffUtil.exponentialBackoff(1, 5_000L)
            assertTrue(
                result in 1500..2500,
                "Expected result in [1500, 2500] but got $result"
            )
        }

        // attempt 1, maxBackoff = 1000: 2^1 * 1000 = 2000, capped to 1000, jitter → [750, 1250]
        repeat(50) {
            val result = BackoffUtil.exponentialBackoff(1, 1_000L)
            assertTrue(
                result in 750..1250,
                "Expected result in [750, 1250] but got $result"
            )
        }
    }

    @Test
    fun `small maxBackoff caps exponential growth`() {
        // attempt 5 with maxBackoff = 3000: 2^5 * 1000 = 32000, capped to 3000
        // jitter 0.75-1.25 → [2250, 3750]
        repeat(100) {
            val result = BackoffUtil.exponentialBackoff(5, 3_000L)
            assertTrue(
                result in 2250..3750,
                "Expected result in [2250, 3750] but got $result"
            )
        }
    }

    @Test
    fun `jitter produces varied results over 100 iterations`() {
        val results = (1..100).map {
            BackoffUtil.exponentialBackoff(3, 60_000L)
        }.toSet()

        // With random jitter, we should get more than just 1 unique value
        assertTrue(
            results.size > 1,
            "Expected varied results from jitter but got ${results.size} unique values"
        )
    }

    @Test
    fun `attempt 0 throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            BackoffUtil.exponentialBackoff(0, 60_000L)
        }
    }

    @Test
    fun `negative attempt throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            BackoffUtil.exponentialBackoff(-1, 60_000L)
        }
    }

    @Test
    fun `result is always positive`() {
        repeat(100) {
            val result = BackoffUtil.exponentialBackoff(1, 60_000L)
            assertTrue(result > 0, "Expected positive result but got $result")
        }
    }

    @Test
    fun `attempt 3 with maxBackoff 60000 returns value in expected range`() {
        // 2^3 * 1000 = 8000, jitter 0.75-1.25 → [6000, 10000]
        repeat(100) {
            val result = BackoffUtil.exponentialBackoff(3, 60_000L)
            assertTrue(
                result in 6000..10000,
                "Expected result in [6000, 10000] but got $result"
            )
        }
    }

    @Test
    fun `boundary attempt 30 is max bounded attempt`() {
        // 2^30 * 1000 = 1073741824000, capped to maxBackoff
        val maxBackoff = 120_000L
        repeat(50) {
            val result = BackoffUtil.exponentialBackoff(30, maxBackoff)
            assertTrue(
                result in 90000..150000,
                "Expected capped result but got $result"
            )
        }
    }

    @Test
    fun `attempt 31 same as 30 due to coercion`() {
        val maxBackoff = 120_000L
        // Both coerce to 30, so behavior should be identical range
        val results30 = (1..200).map { BackoffUtil.exponentialBackoff(30, maxBackoff) }
        val results31 = (1..200).map { BackoffUtil.exponentialBackoff(31, maxBackoff) }

        val min30 = results30.min()
        val max30 = results30.max()
        val min31 = results31.min()
        val max31 = results31.max()

        // They should be in the same range (jitter makes exact comparison impossible)
        // Both should be capped at maxBackoff, so range is [maxBackoff*0.75, maxBackoff*1.25]
        val expectedMin = (maxBackoff * 0.75).toLong()
        val expectedMax = (maxBackoff * 1.25).toLong()

        assertTrue(min30 >= expectedMin, "min30=$min30 expected >= $expectedMin")
        assertTrue(max30 <= expectedMax, "max30=$max30 expected <= $expectedMax")
        assertTrue(min31 >= expectedMin, "min31=$min31 expected >= $expectedMin")
        assertTrue(max31 <= expectedMax, "max31=$max31 expected <= $expectedMax")
    }

    @Test
    fun `maxBackoff of 1 returns values in 0 to 1 range`() {
        repeat(100) {
            val result = BackoffUtil.exponentialBackoff(1, 1L)
            // 1 * jitter(0.75..1.25) → toLong → [0, 1]
            assertTrue(
                result in 0..1,
                "Expected result in [0, 1] but got $result"
            )
        }
    }
}
