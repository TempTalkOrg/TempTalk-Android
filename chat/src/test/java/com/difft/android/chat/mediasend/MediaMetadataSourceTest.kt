package com.difft.android.chat.mediasend

import com.difft.android.test.builders.LocalMediaBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T34 — [MediaMetadataSource] returns the MediaStore values when they are populated and -1
 * (never 0) when they are not, so no caller can render a fabricated "0:00" / "0.0MB".
 *
 * Plain JUnit: both accessors are pure arithmetic over already-loaded fields, no `Uri`/`Context`.
 *
 * Run: :chat:testDebugUnitTest
 */
class MediaMetadataSourceTest {

    /** Populated MediaStore row: both values pass through unchanged. */
    @Test
    fun `populated size and duration are returned as-is`() {
        val media = LocalMediaBuilder.gallery(mime = "video/mp4", size = 1_500_000L, durationMs = 8000L)

        assertEquals(1_500_000L, MediaMetadataSource.sizeBytes(media))
        assertEquals(8000L, MediaMetadataSource.durationMs(media, probedMs = 0L))
    }

    /** Unpopulated row: -1 for both, never 0. */
    @Test
    fun `unknown size and duration are minus one never zero`() {
        val media = LocalMediaBuilder.gallery(mime = "video/mp4", size = 0L, durationMs = 0L)

        assertEquals(-1L, MediaMetadataSource.sizeBytes(media))
        assertEquals(-1L, MediaMetadataSource.durationMs(media, probedMs = 0L))
    }

    /** A usable probed duration wins over the MediaStore duration. */
    @Test
    fun `probed duration wins over the MediaStore duration`() {
        val media = LocalMediaBuilder.gallery(mime = "video/mp4", size = 1_500_000L, durationMs = 8000L)

        assertEquals(3000L, MediaMetadataSource.durationMs(media, probedMs = 3000L))
    }

    /** A probed 0 is "not probed", not "zero length" — fall back to the MediaStore value. */
    @Test
    fun `probed zero falls back to the MediaStore duration`() {
        val media = LocalMediaBuilder.gallery(mime = "video/mp4", durationMs = 8000L)

        assertEquals(8000L, MediaMetadataSource.durationMs(media, probedMs = 0L))
    }
}
