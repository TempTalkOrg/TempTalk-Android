package com.difft.android.call.ui.video

import android.os.SystemClock
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink






typealias FrameSizeListener = (width: Int, height: Int, seenAtNanos: Long) -> Unit








internal class FrameSizeProbe(
    private val target: VideoSink?,
    private val post: (Runnable) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val onSize: FrameSizeListener,
) : VideoSink {
    
    @Volatile
    private var last = NONE

    @Volatile
    private var closed = false

    override fun onFrame(frame: VideoFrame) {
        report(frame.rotatedWidth, frame.rotatedHeight)
        target?.onFrame(frame)
    }

    
    fun report(width: Int, height: Int) {
        if (closed || width <= 0 || height <= 0) return
        val packed = (width.toLong() shl Int.SIZE_BITS) or height.toLong()
        if (packed == last) return
        last = packed
        val seenAt = clock()
        post(Runnable { if (!closed) onSize(width, height, seenAt) })
    }

    
    fun resetDedupe() {
        last = NONE
    }

    
    fun close() {
        closed = true
    }

    private companion object {
        const val NONE = -1L
    }
}
