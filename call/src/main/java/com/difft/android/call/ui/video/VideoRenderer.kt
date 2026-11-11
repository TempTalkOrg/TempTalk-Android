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

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.util.lerp
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
import kotlinx.coroutines.channels.Channel
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
    ScreenShare,
}





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
    draggable: Boolean = false,
    reconnectGeneration: Int = 0,
    onFrameAspect: ((Float) -> Unit)? = null,
    onFrameSize: FrameSizeListener? = null,
    zoomResetKey: Any? = null,
    
    zoomResetRequest: Int = 0,
    firstFrameGate: FirstFrameGate? = null,
    
    hidden: Boolean = false,
    
    
    panSlack: Offset = Offset.Zero,
    
    
    onEdgeColors: ((EdgeColors) -> Unit)? = null,
) {
    
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
    
    val lastAspect = remember { LastAspect() }
    
    var boundProbe by remember { mutableStateOf<FrameSizeProbe?>(null) }
    val currentFirstFrameGate by rememberUpdatedState(firstFrameGate)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    
    val frameShapeChanges = remember { Channel<Unit>(Channel.CONFLATED) }

    
    
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

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    fun cleanupVideoTrack() {
        (boundProbe ?: view as? VideoSink)?.let { sink ->
            try {
                boundVideoTrack?.removeRenderer(sink)
            } catch (e: Exception) {
                L.w { "[VideoRenderer] unbindTrack removeRenderer failed: ${e.stackTraceToString()}" }
            }
        }
        boundProbe?.close()
        boundProbe = null
        boundVideoTrack = null
    }
    
    


    fun cleanupAllResourcesSync() {
        if (isReleased) {
            return
        }
        isReleased = true
        
        (boundProbe ?: view as? VideoSink)?.let { sink ->
            try {
                boundVideoTrack?.removeRenderer(sink)
            } catch (e: Exception) {
                L.w { "[VideoRenderer] cleanupAllResourcesSync removeRenderer failed: ${e.stackTraceToString()}" }
            }
        }
        boundProbe?.close()
        boundProbe = null
        
        try {
            videoSinkVisibility.onDispose()
        } catch (e: Exception) {
            L.w { "[VideoRenderer] cleanupAllResourcesSync onDispose failed: ${e.stackTraceToString()}" }
        }
        
        frameStatusListener?.let {
            try {
                (view as? ScreenShareSurfaceViewRenderer)?.removeFrameStatusListener(it)
            } catch (e: Exception) {
                L.w { "[VideoRenderer] cleanupAllResourcesSync removeFrameStatusListener failed: ${e.stackTraceToString()}" }
            }
            frameStatusListener = null
        }
        
        try {
            when (viewType) {
                ViewType.ScreenShare -> {
                    (view as? ScreenShareSurfaceViewRenderer)?.viewVisibility = null
                }
                else -> {
                    
                }
            }
        } catch (e: Exception) {
            L.w { "[VideoRenderer] cleanupAllResourcesSync viewVisibility cleanup failed: ${e.stackTraceToString()}" }
        }
        
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
            L.w { "[VideoRenderer] cleanupAllResourcesSync release failed: ${e.stackTraceToString()}" }
        }
        
        view = null
        boundVideoTrack = null
    }

    fun setupVideoIfNeeded(videoTrack: VideoTrack?, view: Any) {
        if (isReleased) {
            return
        }
        
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
                
                cleanupVideoTrack()
                boundVideoTrack = videoTrack
                boundGeneration = reconnectGeneration
                lastAspect.value = 0f
                if (videoTrack != null) {
                    (view as? VideoSink)?.let { viewSink ->
                        val probe = if (currentOnFrameSize != null) {
                            FrameSizeProbe(viewSink, post = { mainHandler.post(it) }) { width, height, seenAt ->
                                if (!isReleased) {
                                    currentOnFrameSize?.invoke(width, height, seenAt)
                                    frameShapeChanges.trySend(Unit)
                                }
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

    
    LaunchedEffect(panSlack, viewSize) {
        val limitX = (viewSize.width * scaleFactor - viewSize.width) / 2f + panSlack.x
        val limitY = (viewSize.height * scaleFactor - viewSize.height) / 2f + panSlack.y
        offsetX = offsetX.coerceIn(-limitX, limitX)
        offsetY = offsetY.coerceIn(-limitY, limitY)
    }

    
    LaunchedEffect(scaleType, zoomResetKey) {
        scaleFactor = 1f
        offsetX = 0f
        offsetY = 0f
    }

    LaunchedEffect(zoomResetRequest) {
        if (zoomResetRequest == 0) return@LaunchedEffect
        val fromScale = scaleFactor
        val fromX = offsetX
        val fromY = offsetY
        animate(0f, 1f, animationSpec = tween(ZOOM_RESET_MILLIS)) { t, _ ->
            scaleFactor = lerp(fromScale, 1f, t)
            offsetX = lerp(fromX, 0f, t)
            offsetY = lerp(fromY, 0f, t)
        }
    }

    
    
    DisposableEffect(view, mirror) {
        when (viewType) {
            ViewType.Texture -> (view as? TextureViewRenderer)?.setMirror(mirror)
            ViewType.ScreenShare -> (view as? ScreenShareSurfaceViewRenderer)?.setMirror(mirror)
        }
        onDispose { }
    }

    
    val currentOnEdgeColors by rememberUpdatedState(onEdgeColors)
    val sampleEdges = onEdgeColors != null
    LaunchedEffect(view, sampleEdges) {
        val renderer = view as? TextureViewRenderer ?: return@LaunchedEffect
        if (sampleEdges) {
            
            frameShapeChanges.tryReceive()
            sampleEdgeColors(renderer, frameShapeChanges) { colors ->
                if (!isReleased) currentOnEdgeColors?.invoke(colors)
            }
        }
    }

    
    DisposableEffect(view, firstFrameGate) {
        val renderer = view?.takeIf { viewType != ViewType.ScreenShare }
        val gate = firstFrameGate
        if (renderer != null && gate != null) gate.attach(renderer)
        onDispose { if (renderer != null) gate?.detach(renderer) }
    }

    
    
    val gateGeneration = firstFrameGate?.generation
    LaunchedEffect(frameReporter, gateGeneration) {
        if (!isReleased) frameReporter?.arm()
    }

    
    
    
    DisposableEffect(currentCompositeKeyHash.toString()) {
        onDispose {
            
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

                        detectTransformGestures { _, pan, zoom, _ ->
                            scaleFactor = (scaleFactor * zoom).coerceIn(1.0f, MAX_ZOOM)

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

private const val MAX_ZOOM = 8f
private const val ZOOM_RESET_MILLIS = 250


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
