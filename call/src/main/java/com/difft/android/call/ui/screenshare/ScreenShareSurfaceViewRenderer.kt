package com.difft.android.call.ui.screenshare

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.receiver.FrameStatusListener
import io.livekit.android.room.track.video.ViewVisibility
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoFrame
import java.util.Collections

class ScreenShareSurfaceViewRenderer : SurfaceViewRenderer, ViewVisibility.Notifier {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    private var initialized = false
    @Volatile private var hasFirstFrameRendered = false
    @Volatile private var dismissPlaceholderView = false
    @Volatile private var firstFrameJustRendered = false
    @Volatile private var isReleased = false

    private val frameStatusListeners = Collections.synchronizedList(mutableListOf<FrameStatusListener>())

    override var viewVisibility: ViewVisibility? = null

    override fun init(sharedContext: EglBase.Context?, rendererEvents: RendererCommon.RendererEvents?, configAttributes: IntArray?, drawer: RendererCommon.GlDrawer?) {
        if (initialized) {
            L.w {"[Call] ScreenShare Reinitializing already initialized SurfaceViewRenderer."}
        }
        resetFrameState()
        initialized = true
        super.init(sharedContext, rendererEvents, configAttributes, drawer)
    }

    override fun release() {
        
        isReleased = true
        initialized = false
        resetFrameState()
        
        synchronized(frameStatusListeners) {
            frameStatusListeners.clear()
        }
        
        
        viewVisibility = null
        
        try {
            super.release()
        } catch (e: Exception) {
            L.w {"[Call] ScreenShare SurfaceViewRenderer Error during release: ${e.message}."}
        }
    }

    @SuppressLint("LogNotTimber")
    override fun onFrame(frame: VideoFrame) {
        
        if (!initialized || isReleased) {
            if (!initialized) {
                L.e {"[Call] ScreenShare SurfaceViewRenderer Received frame when not initialized! You must call Room.initVideoRenderer(view) before using this view!"}
            }
            return
        }
        
        try {
            super.onFrame(frame)
            if (firstFrameJustRendered) {
                
                firstFrameJustRendered = false
                return
            }
            if (hasFirstFrameRendered && !dismissPlaceholderView) {
                
                dismissPlaceholderView = true
                frameStatusListeners.toList().forEach { it.onDismissPlaceHolderView() }
            }
        } catch (e: RuntimeException) {
            
            if (e.message?.contains("updateTexImage") == true || 
                e.message?.contains("SurfaceTexture") == true) {
                L.w { "[Call] ScreenShare SurfaceViewRenderer Error updating texture, renderer may be released: ${e.message}" }
                isReleased = true
            } else {
                throw e
            }
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        viewVisibility?.recalculate()
    }

    override fun onFirstFrameRendered() {
        super.onFirstFrameRendered()
        if (!hasFirstFrameRendered) {
            L.i {"[Call] ScreenShare SurfaceViewRenderer First frame rendered."}
            firstFrameJustRendered = true
            hasFirstFrameRendered = true
        }
    }

    private fun resetFrameState() {
        hasFirstFrameRendered = false
        dismissPlaceholderView = false
        firstFrameJustRendered = false
    }

    fun addFrameStatusListener(listener: FrameStatusListener) {
        if (!isReleased) {
            synchronized(frameStatusListeners) {
                frameStatusListeners.add(listener)
            }
        }
    }

    fun removeFrameStatusListener(listener: FrameStatusListener) {
        synchronized(frameStatusListeners) {
            frameStatusListeners.remove(listener)
        }
    }
}