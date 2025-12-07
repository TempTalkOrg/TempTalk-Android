package com.difft.android.call

/**
 * Listener for frame availability status during streaming operations.
 * The timeout period for [onNoFrameAvailable] is determined by the implementing class.
 */
interface FrameStatusListener {
    /** When a frame is detected (e.g., the first frame or when streaming resumes). */
    fun onFrameAvailable()

    /** When no frames are detected after the timeout period. */
    fun onNoFrameAvailable()
}