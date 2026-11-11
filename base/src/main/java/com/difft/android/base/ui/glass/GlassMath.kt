package com.difft.android.base.ui.glass


object GlassTuning {
    const val DOWNSAMPLE = 4f
    const val BLUR_RADIUS_DP = 8f
    const val BLUR_SAMPLE_MARGIN_MULTIPLIER = 3f
    const val SATURATION = 1.5f
    const val TINT_ALPHA = 0.6f
    const val SYSTEM_BAR_PROTECTION_ALPHA = 0.32f
    const val SYSTEM_BAR_FADE_DP = 24f
    const val STRIP_TINT_TOP = 0.72f
    const val LENS_SAMPLE_GUARD_PX = 2f
    const val MIN_MEMORY_BYTES = 4L * 1024 * 1024 * 1024
}


object GlassMath {
    fun blurRadiusPx(density: Float, downsample: Float = GlassTuning.DOWNSAMPLE): Float =
        GlassTuning.BLUR_RADIUS_DP * density / downsample

    fun consumerOffset(sourceWindow: Float, consumerWindow: Float): Float = sourceWindow - consumerWindow

    fun canRenderLive(sdkInt: Int, lowRam: Boolean, totalMemoryBytes: Long): Boolean =
        sdkInt >= 31 && !lowRam && totalMemoryBytes >= GlassTuning.MIN_MEMORY_BYTES
}
