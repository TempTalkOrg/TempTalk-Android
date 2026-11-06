package com.difft.android.call.core

import com.difft.android.call.layout.ScreenShare
import kotlin.math.abs

/**
 * The share's size for the layout: its publication's dimensions until the renderer reports a
 * frame, then the frame's rotated size, which also follows a sender that resizes in place (the
 * publication keeps its first dimensions). A new sharer starts from its publication again.
 */
internal class ShareSize {
    private var published: ScreenShare? = null
    private var frame: ScreenShare? = null

    val current: ScreenShare? get() = frame ?: published

    /** The publication's share, or null when the share ended; returns the size to report. */
    fun onPublished(share: ScreenShare?): ScreenShare? {
        published = share
        if (share == null || frame?.sharerId != share.sharerId) frame = null
        return current
    }

    /**
     * A frame of [sharerId]'s share; returns the size to report, or null when its shape did not
     * change (adaptive stream switching layers changes the size, not the shape).
     */
    fun onFrame(sharerId: String, width: Int, height: Int): ScreenShare? {
        if (width <= 0 || height <= 0 || published?.sharerId != sharerId) return null
        val next = ScreenShare(sharerId, width.toDouble(), height.toDouble())
        val shown = current
        if (shown != null && abs(aspectOf(shown) - aspectOf(next)) < ASPECT_EPSILON) return null
        frame = next
        return next
    }

    private fun aspectOf(share: ScreenShare): Double = share.width / share.height

    private companion object {
        const val ASPECT_EPSILON = 0.01
    }
}
