package com.difft.android.base.media

class ImageCompressionPreset private constructor(
    private val dimensionTargets: IntArray,
    val quality: Int,
    val maxBytes: Int
) {
    
    val targets: IntArray get() = dimensionTargets.copyOf()

    companion object {
        val STANDARD = ImageCompressionPreset(intArrayOf(1600, 1024, 768, 512), 70, 1024 * 1024)
        val STANDARD_LOW_MEMORY = ImageCompressionPreset(intArrayOf(768, 512), 70, 1024 * 1024)
    }
}
