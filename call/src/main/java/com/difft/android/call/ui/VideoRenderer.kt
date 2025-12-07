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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.difft.android.base.ui.theme.SfProFont
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.FrameStatusListener
import com.difft.android.call.R
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    var hasFrame by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current // 获取当前配置
    val screenWidth = configuration.screenWidthDp.dp.value // 获取屏幕宽度（dp）
    val screenHeight = configuration.screenHeightDp.dp.value // 获取屏幕高度（dp）

    val coroutineScope = remember { CoroutineScope(Dispatchers.IO + SupervisorJob()) }

    val videoSinkVisibility = remember(room, videoTrack) { ComposeVisibility() }
    var boundVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var view by remember { mutableStateOf<Any?>(null) }


    var scaleFactor by remember { mutableFloatStateOf(1f) }
    var anchor by remember { mutableStateOf(Offset.Zero) } // 缩放锚点
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    var frameStatusListener: FrameStatusListener? = null

    fun cleanupVideoTrack() {
        view?.let{ boundVideoTrack?.removeRenderer(it as VideoSink) }
        boundVideoTrack = null
    }

    fun setupVideoIfNeeded(videoTrack: VideoTrack?, view: Any) {
        if (boundVideoTrack == videoTrack) {
            return
        }

        // 使用协程上下文来执行耗时操作
        coroutineScope.launch {
            cleanupVideoTrack()
            withContext(Dispatchers.Main) {
                boundVideoTrack = videoTrack
                if (videoTrack != null) {
                    (view as? VideoSink)?.let { sink ->
                        if (videoTrack is RemoteVideoTrack) {
                            videoTrack.addRenderer(sink, videoSinkVisibility)
                        } else {
                            videoTrack.addRenderer(sink)
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
            videoSinkVisibility.onDispose()
            cleanupVideoTrack()
            coroutineScope.cancel() // Move this to proper cleanup
            frameStatusListener?.let {
                (view as? ScreenShareSurfaceViewRenderer)?.removeFrameStatusListener(it)
                frameStatusListener = null
            }
        }
    }

    DisposableEffect(currentCompositeKeyHash.toString()) {
        onDispose {
            when (viewType) {
                ViewType.Texture -> (view as TextureViewRenderer).release()
                ViewType.Surface -> (view as SurfaceViewRenderer).release()
                ViewType.ScreenShare -> (view as ScreenShareSurfaceViewRenderer).release()
            }
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
                                hasFrame = true
                            }

                            override fun onNoFrameAvailable() {
                                hasFrame = false
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
        if ((sourceType == Track.Source.SCREEN_SHARE) && !hasFrame) {
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
                            fontFamily = SfProFont,
                            fontWeight = FontWeight(400),
                            color = colorResource(id = com.difft.android.base.R.color.t_third),
                        )
                    )
                }
            }
        }

    }

}
