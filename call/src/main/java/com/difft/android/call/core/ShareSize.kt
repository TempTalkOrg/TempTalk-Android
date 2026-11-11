package com.difft.android.call.core

import com.difft.android.call.layout.ScreenShare
import kotlin.math.abs






internal class ShareSize {
    private var published: ScreenShare? = null
    private var frame: ScreenShare? = null

    val current: ScreenShare? get() = frame ?: published

    
    fun onPublished(share: ScreenShare?): ScreenShare? {
        published = share
        if (share == null || frame?.sharerId != share.sharerId) frame = null
        return current
    }

    



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
