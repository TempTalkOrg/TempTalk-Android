package com.difft.android.call.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.difft.android.call.FrameStatusListener
import io.livekit.android.room.track.video.ViewVisibility
import io.livekit.android.util.LKLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoFrame
import java.util.Collections

class ScreenShareSurfaceViewRenderer : SurfaceViewRenderer, ViewVisibility.Notifier {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    private val checkIntervalMs: Long = 2000L // Inspection interval in milliseconds to check for frames
    private val timeoutMs: Long = 5000L // If there is no frame data for more than 5 seconds, it is considered that there is no video stream.
    private var monitoringJob: Job? = null
    private var lastFrameTime = 0L
    private var initialized = false
    private var hasFrame = false

    private val frameStatusListeners = Collections.synchronizedList(mutableListOf<FrameStatusListener>())

    override var viewVisibility: ViewVisibility? = null

    override fun init(sharedContext: EglBase.Context?, rendererEvents: RendererCommon.RendererEvents?, configAttributes: IntArray?, drawer: RendererCommon.GlDrawer?) {
        if (initialized) {
            LKLog.Companion.w { "Reinitializing already initialized SurfaceViewRenderer." }
        }
        initialized = true
        startMonitor()
        super.init(sharedContext, rendererEvents, configAttributes, drawer)
    }

    override fun release() {
        initialized = false
        stopMonitor()
        super.release()
    }

    @SuppressLint("LogNotTimber")
    override fun onFrame(frame: VideoFrame) {
        if (!initialized) {
            Log.e("SurfaceViewRenderer", "Received frame when not initialized! You must call Room.initVideoRenderer(view) before using this view!")
        }
        super.onFrame(frame)
        lastFrameTime = SystemClock.elapsedRealtime()

        if (!hasFrame) {
            hasFrame = true
            frameStatusListeners.forEach { it.onFrameAvailable() }
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        viewVisibility?.recalculate()
    }

    override fun onFirstFrameRendered() {
        super.onFirstFrameRendered()
        lastFrameTime = SystemClock.elapsedRealtime()
        if (!hasFrame) {
            hasFrame = true
            frameStatusListeners.forEach { it.onFrameAvailable() }
        }
    }

    private fun stopMonitor() {
        monitoringJob?.cancel()
    }

    private fun startMonitor() {
        stopMonitor()
        lastFrameTime = SystemClock.elapsedRealtime()
        monitoringJob = CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            while (isActive) {
                delay(checkIntervalMs)
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameTime > timeoutMs) {
                    // No frame timed out, notify the listener.
                    hasFrame = false
                    frameStatusListeners.forEach { it.onNoFrameAvailable() }
                }
            }
        }
    }

    fun addFrameStatusListener(listener: FrameStatusListener) {
        frameStatusListeners.add(listener)
    }

    fun removeFrameStatusListener(listener: FrameStatusListener) {
        frameStatusListeners.remove(listener)
    }
}