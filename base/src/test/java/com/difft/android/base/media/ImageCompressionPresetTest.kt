package com.difft.android.base.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCompressionPresetTest {
    @Test
    fun standardAndLowMemoryShareQualityAndByteLimit() {
        assertArrayEquals(intArrayOf(1600, 1024, 768, 512), ImageCompressionPreset.STANDARD.targets)
        assertArrayEquals(intArrayOf(768, 512), ImageCompressionPreset.STANDARD_LOW_MEMORY.targets)
        listOf(ImageCompressionPreset.STANDARD, ImageCompressionPreset.STANDARD_LOW_MEMORY).forEach {
            assertEquals(70, it.quality)
            assertEquals(1024 * 1024, it.maxBytes)
        }
    }

    @Test
    fun changingReturnedTargetsDoesNotChangePreset() {
        ImageCompressionPreset.STANDARD.targets[0] = 1
        assertEquals(1600, ImageCompressionPreset.STANDARD.targets[0])
    }
}
