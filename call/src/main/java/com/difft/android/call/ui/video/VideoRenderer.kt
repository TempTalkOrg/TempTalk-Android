/*
 * Copyright 2023-2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.difft.android.call.ui.video

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.os.Handler
import android.os.Looper
import android.view.View
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.receiver.FrameStatusListener
import com.difft.android.call.R
import com.difft.android.call.ui.screenshare.ScreenShareSurfaceViewRenderer
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.VideoSink

enum class ScaleType {
    FitInside,
    Fill,
}

/** Camera tiles use [Texture] (it moves and resizes with its card); a share keeps its own SurfaceView. */
enum class ViewType {
    Texture,
    ScreenShare,
}

/**
 * Widget for displaying a VideoTrack. Handles the Compose <-> AndroidView interop needed to use
 * [TextureViewRenderer].
 */
@Composable
fun VideoRenderer(
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier,
    room: Room,
    videoTrack: VideoTrack?,
    sourceType: Track.Source,
    mirror: Boolean = false,
    scaleType: ScaleType = ScaleType.Fill,
    viewType: ViewType = ViewType.Texture,
    draggable: Boolean = true,
    reconnectGeneration: Int = 0,
    onFrameAspect: ((Float) -> Unit)? = null,
    onFrameSize: FrameSizeListener? = null,
    zoomResetKey: Any? = null,
    firstFrameGate: FirstFrameGate? = null,
    // Kept composed but not shown (picture in picture): the view goes INVISIBLE and adaptive stream pauses it.
    hidden: Boolean = false,
    // How far (px) the view reaches past the visible area on each side, e.g. a share filling the
    // window: a drag may move it that far even unzoomed.
    panSlack: Offset = Offset.Zero,
    // Non-null while the card shows an ambient letterbox: the frame's edge colours, sampled twice a second.
    onEdgeColors: ((EdgeColors) -> Unit)? = null,
) {
    // Show a black box for preview.
    if (LocalView.current.isInEditMode) {
        Box(
            modifier = Modifier
                .background(Color.Black)
                .then(modifier)
        )
        return
    }

    var dismissPlaceHolderView by remember { mutableStateOf(false) }

    val videoSinkVisibility = remember(room, videoTrack) {
        if (sourceType == Track.Source.SCREEN_SHARE) ScreenShareVisibility() else ComposeVisibility()
    }
    var boundVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    // 已绑定的"重绑代"。当 reconnectGeneration 变化（重订阅完成）时，即使 track 对象未变，
    // 也强制对当前 track 重新 removeRenderer+addRenderer，刷新 sink 让新帧恢复。
    var boundGeneration by remember { mutableIntStateOf(Int.MIN_VALUE) }
    var view by remember { mutableStateOf<Any?>(null) }


    var scaleFactor by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    var frameStatusListener: FrameStatusListener? = null
    var isReleased by remember { mutableStateOf(false) }

    val currentOnFrameAspect by rememberUpdatedState(onFrameAspect)
    val currentOnFrameSize by rememberUpdatedState(onFrameSize)
    val currentPanSlack by rememberUpdatedState(panSlack)
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // The aspect last reported; reset whenever a different track or generation is bound.
    val lastAspect = remember { LastAspect() }
    // Reports frame sizes from the bound track; one per binding, so a rebind always reports again.
    var boundProbe by remember { mutableStateOf<FrameSizeProbe?>(null) }
    val currentFirstFrameGate by rememberUpdatedState(firstFrameGate)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Reports the rotated frame aspect on the main thread; renderers call back on their render/decode thread.
    // A main-looper Handler (not View.post) so a detached view cannot hold the update back; dropped once released.
    fun frameAspectEvents(): RendererCommon.RendererEvents? {
        if (onFrameAspect == null) return null
        return object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() = Unit

            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                if (videoWidth <= 0 || videoHeight <= 0) return
                val aspect = if (rotation % 180 == 0) {
                    videoWidth.toFloat() / videoHeight
                } else {
                    videoHeight.toFloat() / videoWidth
                }
                if (aspect == lastAspect.value) return
                lastAspect.value = aspect
                mainHandler.post {
                    if (!isReleased) currentOnFrameAspect?.invoke(aspect)
                }
            }
        }
    }
    
    var frameReporter by remember { mutableStateOf<FirstFrameReporter?>(null) }

    // Reports this renderer's first frame once it is drawn to the surface, not merely received:
    // RendererEvents.onFirstFrameRendered fires on receipt, before a TextureView can show anything.
    // Scale 0 skips the bitmap readback.
    fun armFirstFrame(renderer: Any) {
        if (firstFrameGate == null) return
        val reporter = FirstFrameReporter(
            renderer = renderer,
            gate = { currentFirstFrameGate },
            addListener = { listener ->
                (renderer as? TextureViewRenderer)?.addFrameListener(listener, 0f)
            },
            postToMain = { report -> mainHandler.post { if (!isReleased) report() } },
        )
        frameReporter = reporter
        reporter.arm()
    }

    // 获取 LifecycleOwner 以检查 Activity 状态
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    fun cleanupVideoTrack() {
        // 确保在主线程执行，避免线程安全问题
        (boundProbe ?: view as? VideoSink)?.let { sink ->
            try {
                boundVideoTrack?.removeRenderer(sink)
            } catch (e: Exception) {
                // 忽略已释放的 renderer 导致的异常
                L.w { "[VideoRenderer] unbindTrack removeRenderer failed: ${e.stackTraceToString()}" }
            }
        }
        boundProbe?.close()
        boundProbe = null
        boundVideoTrack = null
    }
    
    /**
     * 同步清理所有资源，确保在 Activity 销毁前立即执行
     */
    fun cleanupAllResourcesSync() {
        if (isReleased) {
            return
        }
        isReleased = true
        
        // 1. 先清理 video track 的 renderer 引用
        (boundProbe ?: view as? VideoSink)?.let { sink ->
            try {
                boundVideoTrack?.removeRenderer(sink)
            } catch (e: Exception) {
                // 忽略已释放的 renderer 导致的异常
                L.w { "[VideoRenderer] cleanupAllResourcesSync removeRenderer failed: ${e.stackTraceToString()}" }
            }
        }
        boundProbe?.close()
        boundProbe = null
        
        // 2. 清理 videoSinkVisibility
        try {
            videoSinkVisibility.onDispose()
        } catch (e: Exception) {
            // 忽略异常
            L.w { "[VideoRenderer] cleanupAllResourcesSync onDispose failed: ${e.stackTraceToString()}" }
        }
        
        // 3. 清理 ScreenShareSurfaceViewRenderer 的监听器
        frameStatusListener?.let {
            try {
                (view as? ScreenShareSurfaceViewRenderer)?.removeFrameStatusListener(it)
            } catch (e: Exception) {
                // 忽略已释放的 view 导致的异常
                L.w { "[VideoRenderer] cleanupAllResourcesSync removeFrameStatusListener failed: ${e.stackTraceToString()}" }
            }
            frameStatusListener = null
        }
        
        // 4. 清理 viewVisibility 引用
        try {
            when (viewType) {
                ViewType.ScreenShare -> {
                    (view as? ScreenShareSurfaceViewRenderer)?.viewVisibility = null
                }
                else -> {
                    // 其他类型可能也有 viewVisibility，但 ScreenShare 是主要问题
                }
            }
        } catch (e: Exception) {
            // 忽略清理 viewVisibility 时的异常
            L.w { "[VideoRenderer] cleanupAllResourcesSync viewVisibility cleanup failed: ${e.stackTraceToString()}" }
        }
        
        // 5. 释放 renderer
        try {
            when (viewType) {
                ViewType.Texture -> {
                    (view as? TextureViewRenderer)?.release()
                }
                ViewType.ScreenShare -> {
                    (view as? ScreenShareSurfaceViewRenderer)?.release()
                }
            }
        } catch (e: Exception) {
            // 忽略释放过程中的异常
            L.w { "[VideoRenderer] cleanupAllResourcesSync release failed: ${e.stackTraceToString()}" }
        }
        
        // 6. 清理所有引用
        view = null
        boundVideoTrack = null
    }

    fun setupVideoIfNeeded(videoTrack: VideoTrack?, view: Any) {
        // 如果已经释放，不再设置新的 video track
        if (isReleased) {
            return
        }
        
        // 检查 Activity 生命周期状态，如果已销毁则不再执行
        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            cleanupAllResourcesSync()
            return
        }
        
        if (boundVideoTrack == videoTrack && boundGeneration == reconnectGeneration) {
            return
        }

        coroutineScope.launch {
            withContext(Dispatchers.Main) {
                if (isReleased || lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                    cleanupAllResourcesSync()
                    return@withContext
                }
                if (boundVideoTrack == videoTrack && boundGeneration == reconnectGeneration) {
                    return@withContext
                }
                // 重连重绑：先解绑旧 sink（同对象/异对象皆可），再绑到当前 track，刷新帧流。
                cleanupVideoTrack()
                boundVideoTrack = videoTrack
                boundGeneration = reconnectGeneration
                lastAspect.value = 0f
                if (videoTrack != null) {
                    (view as? VideoSink)?.let { viewSink ->
                        val probe = if (currentOnFrameSize != null) {
                            FrameSizeProbe(viewSink, post = { mainHandler.post(it) }) { width, height, seenAt ->
                                if (!isReleased) currentOnFrameSize?.invoke(width, height, seenAt)
                            }
                        } else {
                            null
                        }
                        boundProbe = probe
                        val sink: VideoSink = probe ?: viewSink
                        try {
                            if (videoTrack is RemoteVideoTrack) {
                                videoTrack.addRenderer(sink, videoSinkVisibility)
                            } else {
                                videoTrack.addRenderer(sink)
                            }
                        } catch (e: Exception) {
                            L.w { "[VideoRenderer] addRenderer failed: ${e.stackTraceToString()}" }
                        }
                    }
                }
            }
        }
    }

    // Keeps a live renderer in step with scaleType; the factory only covers the first value.
    DisposableEffect(view, scaleType) {
        view?.let { applyScaling(it, scaleType) }
        onDispose { }
    }

    DisposableEffect(videoSinkVisibility, hidden) {
        videoSinkVisibility.forceHidden = hidden
        onDispose { }
    }

    DisposableEffect(view, hidden) {
        (view as? View)?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        onDispose { }
    }

    // A smaller pan range (a share's region changed) pulls an existing pan back inside it.
    LaunchedEffect(panSlack, viewSize) {
        val limitX = (viewSize.width * scaleFactor - viewSize.width) / 2f + panSlack.x
        val limitY = (viewSize.height * scaleFactor - viewSize.height) / 2f + panSlack.y
        offsetX = offsetX.coerceIn(-limitX, limitX)
        offsetY = offsetY.coerceIn(-limitY, limitY)
    }

    // A zoom/pan made for one scaling mode or slot size does not carry over to another.
    LaunchedEffect(scaleType, zoomResetKey) {
        scaleFactor = 1f
        offsetX = 0f
        offsetY = 0f
    }

    // view 是本 effect 的 key：首次组合时 AndroidView 的 factory 可能尚未给 view 赋值（仍为 null），
    // 重连重建 renderer 时该时序更易触发。用安全转换跳过 null，待 factory 设置 view（null→非空）
    // 后本 effect 会因 key 变化自动重跑并应用 mirror，既不崩溃也不丢镜像设置。
    DisposableEffect(view, mirror) {
        when (viewType) {
            ViewType.Texture -> (view as? TextureViewRenderer)?.setMirror(mirror)
            ViewType.ScreenShare -> (view as? ScreenShareSurfaceViewRenderer)?.setMirror(mirror)
        }
        onDispose { }
    }

    // Only camera renderers (TextureView) sample; a share keeps its plain bands.
    val currentOnEdgeColors by rememberUpdatedState(onEdgeColors)
    val sampleEdges = onEdgeColors != null
    LaunchedEffect(view, sampleEdges) {
        val renderer = view as? TextureViewRenderer ?: return@LaunchedEffect
        if (sampleEdges) sampleEdgeColors(renderer) { colors -> if (!isReleased) currentOnEdgeColors?.invoke(colors) }
    }

    // The screen-share renderer keeps its own placeholder and never arms the gate.
    DisposableEffect(view, firstFrameGate) {
        val renderer = view?.takeIf { viewType != ViewType.ScreenShare }
        val gate = firstFrameGate
        if (renderer != null && gate != null) gate.attach(renderer)
        onDispose { if (renderer != null) gate?.detach(renderer) }
    }

    // A gate reset (camera off) on this kept renderer needs a fresh one-shot listener; the factory's
    // was spent on the stale image. The reporter skips arming while one is still pending.
    val gateGeneration = firstFrameGate?.generation
    LaunchedEffect(frameReporter, gateGeneration) {
        if (!isReleased) frameReporter?.arm()
    }

    // 注意：不要以 videoTrack 为 key 做清理。全量重连会用**新的** track 对象替换旧 track，
    // 若 onDispose 在 track 变化时执行 cleanupAllResourcesSync()（含 release()）会黑屏、丢最后一帧。
    // track 的 old→new 解绑/重绑统一由 `update` 的 setupVideoIfNeeded 原地完成（removeRenderer →
    // addRenderer，不 release/clearImage）。真正的 release 只交给下面 currentCompositeKeyHash 的
    // onDispose（renderer 真正离开 composition：participant 离开 / 通话结束）。
    DisposableEffect(currentCompositeKeyHash.toString()) {
        onDispose {
            // 同步清理所有资源，确保在 Activity 销毁前立即执行
            // 这是防止内存泄漏的关键：必须在 DisposableEffect 中同步清理
            cleanupAllResourcesSync()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                when (viewType) {
                    ViewType.Texture -> TextureViewRenderer(context).apply {
                        room.initVideoRenderer(this, frameAspectEvents())
                        armFirstFrame(this)
                        setupVideoIfNeeded(videoTrack, this)

                        applyScaling(this, scaleType)
                        view = this
                    }
                    ViewType.ScreenShare -> ScreenShareSurfaceViewRenderer(context).apply {
                        room.initVideoRenderer(this)
                        setupVideoIfNeeded(videoTrack, this)

                        frameStatusListener = object : FrameStatusListener {
                            override fun onFrameAvailable() {
                            }

                            override fun onNoFrameAvailable() {
                                // 占位图仅用于首次加载首帧之前，后续不再展示
                            }

                            override fun onFirstFrameRendered() {
                            }

                            override fun onDismissPlaceHolderView() {
                                dismissPlaceHolderView = true
                            }
                        }

                        frameStatusListener?.let {
                            this.addFrameStatusListener(it)
                        }

                        applyScaling(this, scaleType)
                        view = this
                    }
                }
            },
            update = { v -> setupVideoIfNeeded(videoTrack, v) },
            modifier = modifier
                .onGloballyPositioned { videoSinkVisibility.onGloballyPositioned(it) }
                .onSizeChanged { viewSize = it }
                .pointerInput(draggable) {
                    if(draggable){

                        val maxScale = 10f

                        detectTransformGestures { _, pan, zoom, _ ->
                            scaleFactor = (scaleFactor * zoom).coerceIn(1.0f, maxScale)

                            val slack = currentPanSlack
                            if (scaleFactor <= 1f && slack == Offset.Zero) {
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                offsetX += pan.x
                                offsetY += pan.y

                                val offsetXLimit = (size.width * scaleFactor - size.width) / 2f + slack.x
                                val offsetYLimit = (size.height * scaleFactor - size.height) / 2f + slack.y

                                offsetX = offsetX.coerceIn(-offsetXLimit, offsetXLimit)
                                offsetY = offsetY.coerceIn(-offsetYLimit, offsetYLimit)
                            }
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scaleFactor
                    scaleY = scaleFactor
                    translationX = offsetX
                    translationY = offsetY
                }
        )

        // 占位 UI（根据 hasFrame 控制显示）
        if ((sourceType == Track.Source.SCREEN_SHARE) && !dismissPlaceHolderView) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(DifftTheme.colors.background),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.Top),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(132.dp)
                        .height(47.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.tabler_aspect_ratio),
                        contentDescription = "screen_sharing_placeholder",
                        contentScale = ContentScale.None,
                        modifier = Modifier
                            .padding(0.83333.dp)
                            .width(20.dp)
                            .height(20.dp)
                    )

                    Text(
                        text = ResUtils.getString(R.string.call_screen_sharing_placeholder),
                        style = DifftTheme.typography.p3,
                        color = DifftTheme.colors.textTertiary,
                    )
                }
            }
        }

    }

}

/** Written on the render thread, reset on main when a track is bound. */
private class LastAspect {
    @Volatile
    var value = 0f
}

private fun applyScaling(view: Any, scaleType: ScaleType) {
    val scaling = when (scaleType) {
        ScaleType.FitInside -> RendererCommon.ScalingType.SCALE_ASPECT_FIT
        ScaleType.Fill -> RendererCommon.ScalingType.SCALE_ASPECT_FILL
    }
    when (view) {
        is TextureViewRenderer -> view.setScalingType(scaling)
        is livekit.org.webrtc.SurfaceViewRenderer -> view.setScalingType(scaling)
    }
}
