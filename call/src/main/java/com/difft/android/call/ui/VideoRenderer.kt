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

package com.difft.android.call.ui

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.receiver.FrameStatusListener
import com.difft.android.call.R
import io.livekit.android.renderer.SurfaceViewRenderer
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

enum class ViewType {
    Texture,
    Surface,
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

    val configuration = LocalConfiguration.current // 获取当前配置
    val screenWidth = configuration.screenWidthDp.dp.value // 获取屏幕宽度（dp）
    val screenHeight = configuration.screenHeightDp.dp.value // 获取屏幕高度（dp）

    val videoSinkVisibility = remember(room, videoTrack) { ComposeVisibility() }
    var boundVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var view by remember { mutableStateOf<Any?>(null) }


    var scaleFactor by remember { mutableFloatStateOf(1f) }
    var anchor by remember { mutableStateOf(Offset.Zero) } // 缩放锚点
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    var frameStatusListener: FrameStatusListener? = null
    var isReleased by remember { mutableStateOf(false) }
    
    // 获取 LifecycleOwner 以检查 Activity 状态
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    fun cleanupVideoTrack() {
        // 确保在主线程执行，避免线程安全问题
        view?.let { renderer ->
            try {
                boundVideoTrack?.removeRenderer(renderer as VideoSink)
            } catch (e: Exception) {
                // 忽略已释放的 renderer 导致的异常
                L.w { "[VideoRenderer] unbindTrack removeRenderer failed: ${e.stackTraceToString()}" }
            }
        }
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
        view?.let { renderer ->
            try {
                boundVideoTrack?.removeRenderer(renderer as VideoSink)
            } catch (e: Exception) {
                // 忽略已释放的 renderer 导致的异常
                L.w { "[VideoRenderer] cleanupAllResourcesSync removeRenderer failed: ${e.stackTraceToString()}" }
            }
        }
        
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
                ViewType.Surface -> {
                    (view as? SurfaceViewRenderer)?.release()
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
        
        if (boundVideoTrack == videoTrack) {
            return
        }

        // 使用协程上下文来执行耗时操作
        coroutineScope.launch {
            // 确保在主线程执行 cleanupVideoTrack，因为 removeRenderer 可能涉及 UI 操作
            withContext(Dispatchers.Main) {
                // 再次检查状态，防止在异步操作期间 Activity 被销毁
                if (isReleased || lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                    cleanupAllResourcesSync()
                    return@withContext
                }
                cleanupVideoTrack()
            }
            withContext(Dispatchers.Main) {
                // 再次检查状态，防止在异步操作期间 Activity 被销毁
                if (isReleased || lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                    cleanupAllResourcesSync()
                    return@withContext
                }
                boundVideoTrack = videoTrack
                if (videoTrack != null) {
                    (view as? VideoSink)?.let { sink ->
                        try {
                            if (videoTrack is RemoteVideoTrack) {
                                videoTrack.addRenderer(sink, videoSinkVisibility)
                            } else {
                                videoTrack.addRenderer(sink)
                            }
                        } catch (e: Exception) {
                            // 忽略添加 renderer 时的异常（可能 view 已释放）
                            L.w { "[VideoRenderer] addRenderer failed: ${e.stackTraceToString()}" }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(view, mirror) {
        when (viewType) {
            ViewType.Texture -> (view as TextureViewRenderer).setMirror(mirror)
            ViewType.Surface -> (view as SurfaceViewRenderer).setMirror(mirror)
            ViewType.ScreenShare -> (view as ScreenShareSurfaceViewRenderer).setMirror(mirror)
        }
        onDispose { }
    }

    // And update the DisposableEffect:
    DisposableEffect(room, videoTrack) {
        onDispose {
            // 同步清理所有资源，确保在 Activity 销毁前立即执行
            // 这是防止内存泄漏的关键：必须在 DisposableEffect 中同步清理
            cleanupAllResourcesSync()
        }
    }

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
                        room.initVideoRenderer(this)
                        setupVideoIfNeeded(videoTrack, this)

                        when (scaleType) {
                            ScaleType.FitInside -> {
                                this.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                            }

                            ScaleType.Fill -> {
                                this.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            }
                        }
                        view = this
                    }
                    ViewType.Surface -> SurfaceViewRenderer(context).apply {
                        room.initVideoRenderer(this)
                        setupVideoIfNeeded(videoTrack, this)

                        when (scaleType) {
                            ScaleType.FitInside -> {
                                this.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                            }

                            ScaleType.Fill -> {
                                this.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            }
                        }
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

                        when (scaleType) {
                            ScaleType.FitInside -> {
                                this.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                            }

                            ScaleType.Fill -> {
                                this.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            }
                        }
                        view = this
                    }
                }
            },
            update = { v -> setupVideoIfNeeded(videoTrack, v) },
            modifier = modifier
                .onGloballyPositioned { videoSinkVisibility.onGloballyPositioned(it) }
                .pointerInput(Unit) {
                    if(draggable){

                        val maxScale = 10f

                        detectTransformGestures { centroid, pan, zoom, rotation ->
                            zoom.let { zoomFactor ->
                                // 更新缩放比例，但限制在合理范围内
                                scaleFactor *= zoomFactor
                                scaleFactor = scaleFactor.coerceIn(1.0f, maxScale)
                                // 记录缩放锚点（这里以手势的中心点为锚点）
                                anchor = centroid
                            }
                            pan.let { panAmount ->
                                // 根据缩放比例调整拖拽量
                                val scaledPanX = panAmount.x
                                val scaledPanY = panAmount.y

                                // 更新拖拽偏移量前，先计算新的边界位置
                                var newOffsetX = offsetX + scaledPanX
                                var newOffsetY = offsetY + scaledPanY

                                // 结合缩放比例计算新的边界
                                val offsetXLimit = (screenWidth * scaleFactor - screenWidth)/2f
                                val offsetYLimit = (screenHeight * scaleFactor - screenHeight)/2f

                                newOffsetX = newOffsetX.coerceIn(-offsetXLimit, offsetXLimit)
                                newOffsetY = newOffsetY.coerceIn(-offsetYLimit, offsetYLimit)
                                // 更新拖拽偏移量
                                offsetX = newOffsetX
                                offsetY = newOffsetY

                            }
                        }
                    }
                }
                .graphicsLayer {
                    // 应用缩放和平移变换
                    this.scaleX = scaleFactor
                    this.scaleY = scaleFactor
                    this.translationX = offsetX.dp.toPx()
                    this.translationY = offsetY.dp.toPx()
                }
        )

        // 占位 UI（根据 hasFrame 控制显示）
        if ((sourceType == Track.Source.SCREEN_SHARE) && !dismissPlaceHolderView) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(colorResource(id = com.difft.android.base.R.color.bg1_night)),
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
                        style = TextStyle(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight(400),
                            color = colorResource(id = com.difft.android.base.R.color.t_third),
                        )
                    )
                }
            }
        }

    }

}
