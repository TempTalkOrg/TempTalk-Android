package com.difft.android.selector.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnPlayerListener
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min








@OptIn(UnstableApi::class)
class ExoVideoPlayerEngine : VideoPlayerEngine<PlayerView> {

    private val listeners = CopyOnWriteArrayList<OnPlayerListener>()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    listeners.forEach { it.onPlayerReady() }
                }
                Player.STATE_ENDED -> {
                    listeners.forEach { it.onPlayerEnd() }
                }
                Player.STATE_BUFFERING -> {
                    listeners.forEach { it.onPlayerLoading() }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            listeners.forEach { it.onPlayerError() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateVideoPlayer(context: Context): View {
        return PlayerView(context).apply {
            
            useController = true
            controllerShowTimeoutMs = 3000
            controllerHideOnTouch = false  
            controllerAutoShow = false  
            
            
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener {
                listeners.forEach { it.onPlayerControllerVisibility(this, isControllerFullyVisible) }
            })
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

            
            var scaleFactor = 1.0f
            var isScaling = false

            
            val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.OnScaleGestureListener {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleFactor *= detector.scaleFactor
                    scaleFactor = max(MIN_SCALE, min(scaleFactor, MAX_SCALE))
                    scaleX = scaleFactor
                    scaleY = scaleFactor
                    return true
                }

                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isScaling = true
                    
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isScaling = false
                    
                    if (scaleFactor <= MIN_SCALE) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            })

            
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    
                    if (isControllerFullyVisible) {
                        hideController()
                    } else {
                        showController()
                    }
                    
                    listeners.forEach { it.onPlayerTap() }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    
                    scaleFactor = if (scaleFactor > MIN_SCALE) MIN_SCALE else MID_SCALE
                    animate()
                        .scaleX(scaleFactor)
                        .scaleY(scaleFactor)
                        .setDuration(ZOOM_ANIMATION_DURATION)
                        .start()
                    
                    parent?.requestDisallowInterceptTouchEvent(scaleFactor > MIN_SCALE)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    
                    listeners.forEach { it.onPlayerLongPress() }
                }
            })

            setOnTouchListener { _, event ->
                
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (scaleFactor > MIN_SCALE) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }

                
                
                if (!isControllerFullyVisible) {
                    scaleGestureDetector.onTouchEvent(event)
                }
                
                gestureDetector.onTouchEvent(event)

                
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    if (scaleFactor <= MIN_SCALE && !isScaling) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }

                true 
            }
        }
    }

    override fun onStarPlayer(player: PlayerView, media: LocalMedia) {
        val context = player.context ?: return
        val path = media.availablePath ?: return

        
        var exoPlayer = player.player as? ExoPlayer
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context)
                .setSeekBackIncrementMs(SEEK_INTERVAL_MS)
                .setSeekForwardIncrementMs(SEEK_INTERVAL_MS)
                .build()
            player.player = exoPlayer
        } else {
            
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }

        
        exoPlayer.removeListener(playerListener) 
        exoPlayer.addListener(playerListener)

        
        val config: SelectorConfig? = SelectorProviders.getInstance().selectorConfig
        exoPlayer.repeatMode = if (config?.isLoopAutoPlay == true) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }

        
        val uri: Uri = when {
            PictureMimeType.isContent(path) -> Uri.parse(path)
            PictureMimeType.isHasHttp(path) -> Uri.parse(path)
            else -> File(path).toUri() 
        }

        
        player.hideController()

        
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    override fun onResume(player: PlayerView) {
        (player.player as? ExoPlayer)?.playWhenReady = true
    }

    override fun onPause(player: PlayerView) {
        (player.player as? ExoPlayer)?.playWhenReady = false
    }

    override fun isPlaying(player: PlayerView): Boolean {
        return (player.player as? ExoPlayer)?.isPlaying == true
    }

    override fun addPlayListener(playerListener: OnPlayerListener) {
        if (!listeners.contains(playerListener)) {
            listeners.add(playerListener)
        }
    }

    override fun removePlayListener(playerListener: OnPlayerListener?) {
        if (playerListener != null) {
            listeners.remove(playerListener)
        } else {
            listeners.clear()
        }
    }

    override fun onPlayerAttachedToWindow(player: PlayerView) {
        
    }

    override fun onPlayerDetachedFromWindow(player: PlayerView) {
        (player.player as? ExoPlayer)?.let { exo ->
            exo.removeListener(playerListener)
            exo.stop()
            exo.clearMediaItems()
            exo.release()
        }
        player.player = null
    }

    override fun destroy(player: PlayerView) {
        (player.player as? ExoPlayer)?.let { exo ->
            exo.removeListener(playerListener)
            exo.release()
        }
        player.player = null
        listeners.clear()
    }

    companion object {
        private const val SEEK_INTERVAL_MS = 15_000L
        private const val MIN_SCALE = 1.0f
        private const val MID_SCALE = 2.0f
        private const val MAX_SCALE = 3.0f
        private const val ZOOM_ANIMATION_DURATION = 300L
    }
}