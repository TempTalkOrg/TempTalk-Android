package com.difft.android.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * T33 — [VideoUtil.inputBitRate] arithmetic.
 *
 * Plain JUnit on purpose: pure arithmetic with no `Uri` / `Context`, so Robolectric would only
 * make it slower.
 *
 * Run: :video:testDebugUnitTest
 */
class VideoUtilBitRateTest {

    @Test
    fun `known size and duration yield the bitrate in bits per second`() {
        assertEquals(1_500_000L * 8 / 8, VideoUtil.inputBitRate(1_500_000L, 8_000L).toLong())
    }

    @Test
    fun `unknown duration yields unknown, never zero`() {
        val result = VideoUtil.inputBitRate(1_500_000L, 0L)

        assertEquals(VideoUtil.UNKNOWN_BIT_RATE, result)
        assertNotEquals(0, result)
    }

    @Test
    fun `unknown size yields unknown, never zero`() {
        val result = VideoUtil.inputBitRate(-1L, 8_000L)

        assertEquals(VideoUtil.UNKNOWN_BIT_RATE, result)
        assertNotEquals(0, result)
    }

    @Test
    fun `both unknown yields unknown, never zero`() {
        val result = VideoUtil.inputBitRate(0L, 0L)

        assertEquals(VideoUtil.UNKNOWN_BIT_RATE, result)
        assertNotEquals(0, result)
    }
}
