package com.difft.android.call.ui

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
import java.util.concurrent.atomic.AtomicInteger

class ScreenShareSurfaceViewRenderer : SurfaceViewRenderer, ViewVisibility.Notifier {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    private var initialized = false
    @Volatile private var hasFirstFrameRendered = false
    @Volatile private var dismissPlaceholderView = false
    @Volatile private var firstFrameJustRendered = false
    private val framesLoadedAfterFirstFrame = AtomicInteger(0)
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
        // 标记为已释放，防止后续帧处理
        isReleased = true
        initialized = false
        resetFrameState()
        
        // 清理所有监听器，防止内存泄漏
        synchronized(frameStatusListeners) {
            frameStatusListeners.clear()
        }
        
        // 清理 viewVisibility 引用，防止持有 Activity 引用
        viewVisibility = null
        
        try {
            super.release()
        } catch (e: Exception) {
            // 忽略释放过程中的异常，避免崩溃
            L.w {"[Call] ScreenShare SurfaceViewRenderer Error during release: ${e.message}."}
        }
    }

    @SuppressLint("LogNotTimber")
    override fun onFrame(frame: VideoFrame) {
        // 如果已经释放，不再处理帧，避免 updateTexImage 崩溃
        if (!initialized || isReleased) {
            if (!initialized) {
                L.e {"[Call] ScreenShare SurfaceViewRenderer Received frame when not initialized! You must call Room.initVideoRenderer(view) before using this view!"}
            }
            return
        }
        
        try {
            super.onFrame(frame)
            //  如果刚渲染了第一帧，则不进行后续处理（onFrame、onFirstFrameRendered调用存在并发问题，执行先后顺序可能不同）
            if (firstFrameJustRendered) {
                firstFrameJustRendered = false
                return
            }
            // 如果已经渲染过第一帧，并且之后加载到第二帧时，则通知移除占位符视图（选择第二帧考虑视频画面已稳定加载，不会出现黑屏）
            if (hasFirstFrameRendered && !dismissPlaceholderView) {
                if (framesLoadedAfterFirstFrame.incrementAndGet() == 2) {
                    dismissPlaceholderView = true
                    frameStatusListeners.toList().forEach { it.onDismissPlaceHolderView() }
                }
            }
        } catch (e: RuntimeException) {
            // 捕获 updateTexImage 相关的 RuntimeException，避免崩溃
            // 这通常发生在 SurfaceTexture 已经释放但还在尝试更新纹理时
            if (e.message?.contains("updateTexImage") == true || 
                e.message?.contains("SurfaceTexture") == true) {
                L.w { "[Call] ScreenShare SurfaceViewRenderer Error updating texture, renderer may be released: ${e.message}" }
                // 标记为已释放，防止后续帧处理
                isReleased = true
            } else {
                // 其他异常重新抛出
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
            hasFirstFrameRendered = true
            framesLoadedAfterFirstFrame.set(0)
            firstFrameJustRendered = true
        }
    }

    private fun resetFrameState() {
        hasFirstFrameRendered = false
        dismissPlaceholderView = false
        firstFrameJustRendered = false
        framesLoadedAfterFirstFrame.set(0)
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