package com.luck.picture.lib.engine

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
import com.luck.picture.lib.config.PictureMimeType
import com.luck.picture.lib.config.SelectorConfig
import com.luck.picture.lib.config.SelectorProviders
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnPlayerListener
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

/**
 * ExoPlayer-based VideoPlayerEngine implementation with playback controls.
 * Provides a PlayerView with built-in control bar for video playback in PictureSelector preview.
 *
 * Note: This engine is shared across multiple PreviewVideoHolders, so each PlayerView
 * manages its own ExoPlayer instance via the view's tag.
 */
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
            // Configure PlayerView with controls
            useController = true
            controllerShowTimeoutMs = 3000
            controllerHideOnTouch = false  // We handle controller visibility manually
            controllerAutoShow = false  // Don't auto-show on playback start
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

            // Zoom state for this PlayerView
            var scaleFactor = 1.0f
            var isScaling = false

            // Scale gesture detector for pinch-to-zoom
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
                    // Disable parent interception during scale
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isScaling = false
                    // Allow parent interception when at minimum scale
                    if (scaleFactor <= MIN_SCALE) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            })

            // Gesture detector for tap and double-tap
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    // Toggle controller visibility
                    if (isControllerFullyVisible) {
                        hideController()
                    } else {
                        showController()
                    }
                    // Notify listeners about the tap for UI visibility toggle
                    listeners.forEach { it.onPlayerTap() }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Toggle zoom between 1x and 2x
                    scaleFactor = if (scaleFactor > MIN_SCALE) MIN_SCALE else MID_SCALE
                    animate()
                        .scaleX(scaleFactor)
                        .scaleY(scaleFactor)
                        .setDuration(ZOOM_ANIMATION_DURATION)
                        .start()
                    // Update parent interception based on scale
                    parent?.requestDisallowInterceptTouchEvent(scaleFactor > MIN_SCALE)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    // Notify listeners about long press for save/download actions
                    listeners.forEach { it.onPlayerLongPress() }
                }
            })

            setOnTouchListener { _, event ->
                // Handle touch down - disable parent interception if zoomed
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (scaleFactor > MIN_SCALE) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }

                // Process scale gestures only when controller is hidden
                // This prevents zoom from interfering with control button operations
                if (!isControllerFullyVisible) {
                    scaleGestureDetector.onTouchEvent(event)
                }
                // Process tap/double-tap gestures
                gestureDetector.onTouchEvent(event)

                // Handle touch up - allow parent interception if at min scale
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    if (scaleFactor <= MIN_SCALE && !isScaling) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }

                true // Consume all touch events
            }
        }
    }

    override fun onStarPlayer(player: PlayerView, media: LocalMedia) {
        val context = player.context ?: return
        val path = media.availablePath ?: return

        // Get or create ExoPlayer for this PlayerView
        var exoPlayer = player.player as? ExoPlayer
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context)
                .setSeekBackIncrementMs(SEEK_INTERVAL_MS)
                .setSeekForwardIncrementMs(SEEK_INTERVAL_MS)
                .build()
            player.player = exoPlayer
        } else {
            // Clear previous media when reusing ExoPlayer instance
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }

        // Add listener
        exoPlayer.removeListener(playerListener) // Remove first to avoid duplicates
        exoPlayer.addListener(playerListener)

        // Configure looping based on selector config
        val config: SelectorConfig? = SelectorProviders.getInstance().selectorConfig
        exoPlayer.repeatMode = if (config?.isLoopAutoPlay == true) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }

        // Convert path to proper URI
        val uri: Uri = when {
            PictureMimeType.isContent(path) -> Uri.parse(path)
            PictureMimeType.isHasHttp(path) -> Uri.parse(path)
            else -> File(path).toUri() // Local file path
        }

        // Hide controller before starting playback
        player.hideController()

        // Set media and start playback
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
        // ExoPlayer will be created in onStarPlayer when needed
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