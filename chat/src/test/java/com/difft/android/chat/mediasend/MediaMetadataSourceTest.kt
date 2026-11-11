package com.difft.android.chat.mediasend

import com.difft.android.test.builders.LocalMediaBuilder
import org.junit.Assert.assertEquals
import org.junit.Test









class MediaMetadataSourceTest {

    
    @Test
    fun `populated size and duration are returned as-is`() {
        val media = LocalMediaBuilder.gallery(mime = "video/mp4", size = 1_500_000L, durationMs = 8000L)

        assertEquals(1_500_000L, MediaMetadataSource.sizeBytes(media))
        assertEquals(8000L, MediaMetadataSource.durationMs(media, probedMs = 0L))
    }

    
    @Test
    fun `unknown size and duration are minus one never zero`() {
        val media = LocalMediaBuilder.gallery(mime = "video/mp4", size = 0L, durationMs = 0L)

        assertEquals(-1L, MediaMetadataSource.sizeBytes(media))
        assertEquals(-1L, MediaMetadataSource.durationMs(media, probedMs = 0L))
    }

    
    @Test
    fun `probed duration wins over the MediaStore duration`() {
        val media = LocalMediaBuilder.gallery(mime = "video/mp4", size = 1_500_000L, durationMs = 8000L)

        assertEquals(3000L, MediaMetadataSource.durationMs(media, probedMs = 3000L))
    }

    
    @Test
    fun `probed zero falls back to the MediaStore duration`() {
        val media = LocalMediaBuilder.gallery(mime = "video/mp4", durationMs = 8000L)

        assertEquals(8000L, MediaMetadataSource.durationMs(media, probedMs = 0L))
    }
}
