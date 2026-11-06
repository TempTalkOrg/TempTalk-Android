package com.difft.android.call.ui.video

import android.os.SystemClock
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

/**
 * A video frame's size with rotation applied, delivered on main, with the time the frame was seen
 * ([SystemClock.elapsedRealtimeNanos], read on the frame's thread). The receiver uses that time to
 * drop a size seen before it last reset (a camera turned off since).
 */
typealias FrameSizeListener = (width: Int, height: Int, seenAtNanos: Long) -> Unit

/**
 * A [VideoSink] that reads each frame's rotated size on the frame's thread, reports it once per
 * change through [post] (main), and hands the frame on to [target] in the same call. It never
 * retains a frame. Renderers already report resolution changes, but each renderer view keeps its
 * own last size, so a view rebound to a new track of the same size reports nothing; a probe is made
 * per binding instead, so its first frame is always reported.
 */
internal class FrameSizeProbe(
    private val target: VideoSink?,
    private val post: (Runnable) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val onSize: FrameSizeListener,
) : VideoSink {
    // Width and height packed in one long so a reset and a frame never see half of each other.
    @Volatile
    private var last = NONE

    @Volatile
    private var closed = false

    override fun onFrame(frame: VideoFrame) {
        report(frame.rotatedWidth, frame.rotatedHeight)
        target?.onFrame(frame)
    }

    /** One frame's rotated size; split out so the dedupe is testable without a frame buffer. */
    fun report(width: Int, height: Int) {
        if (closed || width <= 0 || height <= 0) return
        val packed = (width.toLong() shl Int.SIZE_BITS) or height.toLong()
        if (packed == last) return
        last = packed
        val seenAt = clock()
        post(Runnable { if (!closed) onSize(width, height, seenAt) })
    }

    /** The next frame is reported even when its size equals the last one. */
    fun resetDedupe() {
        last = NONE
    }

    /** Drops sizes not yet delivered and every later one. */
    fun close() {
        closed = true
    }

    private companion object {
        const val NONE = -1L
    }
}
