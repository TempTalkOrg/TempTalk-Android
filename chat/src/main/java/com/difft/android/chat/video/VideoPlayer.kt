package com.difft.android.chat.video

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.LegacyPlayerControlView
import androidx.media3.ui.PlayerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.R
import com.difft.android.chat.dependencies.ApplicationDependencies
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class VideoPlayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val exoView: PlayerView
    private val mediaSourceFactory: DefaultMediaSourceFactory

    private var exoPlayer: ExoPlayer? = null
    private var exoControls: LegacyPlayerControlView
    private var window: Window? = null
    private var playerStateCallback: PlayerStateCallback? = null
    private var playerPositionDiscontinuityCallback: PlayerPositionDiscontinuityCallback? = null
    private var playerCallback: PlayerCallback? = null
    private var clipped = false
    private var clippedStartUs: Long = 0
    private val exoPlayerListener: ExoPlayerListener
    private val playerListener: Player.Listener
    private var muted = false
    private var mediaItem: MediaItem? = null

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.VideoPlayer)
        val videoPlayerLayout = typedArray.getResourceId(R.styleable.VideoPlayer_playerLayoutId, R.layout.video_player)
        typedArray.recycle()
        View.inflate(context, videoPlayerLayout, this)

        this.mediaSourceFactory = DefaultMediaSourceFactory(context)

        this.exoView = findViewById(R.id.video_view)
        this.exoControls = createPlayerControls(getContext())

        this.exoPlayerListener = ExoPlayerListener()
        this.playerListener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                onPlaybackStateChanged(playWhenReady, exoPlayer!!.playbackState)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                onPlaybackStateChanged(exoPlayer!!.playWhenReady, playbackState)
            }

            fun onPlaybackStateChanged(playWhenReady: Boolean, playbackState: Int) {
                if (playerCallback != null) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            playerCallback!!.onReady()
                            if (playWhenReady) {
                                playerCallback!!.onPlaying()
                            } else {
                                playerCallback!!.onStopped()
                            }
                        }
                        Player.STATE_ENDED -> playerCallback!!.onStopped()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                L.w(error) { "$TAG A player error occurred" }
                playerCallback?.onError()
            }
        }
    }

    private fun createPlayerControls(context: Context): LegacyPlayerControlView {
        val playerControlView = LegacyPlayerControlView(context)
        playerControlView.setShowTimeoutMs(-1)
        playerControlView.setShowNextButton(false)
        playerControlView.setShowPreviousButton(false)
        return playerControlView
    }

    fun setVideoSource(videoSource: Uri, autoplay: Boolean, poolTag: String) {
        setVideoSource(videoSource, autoplay, poolTag, 0, 0)
    }

    fun setVideoSource(videoSource: Uri, autoplay: Boolean, poolTag: String, clipStartMs: Long, clipEndMs: Long) {
        if (exoPlayer == null) {
            exoPlayer = ApplicationDependencies.getExoPlayerPool().require(poolTag)
            exoPlayer!!.addListener(exoPlayerListener)
            exoPlayer!!.addListener(playerListener)
            exoView.player = exoPlayer
            exoControls.player = exoPlayer
            if (muted) {
                mute()
            }
        }

        mediaItem = MediaItem.fromUri(videoSource).buildUpon()
            .setClippingConfiguration(getClippingConfiguration(clipStartMs, clipEndMs))
            .build()

        exoPlayer!!.setMediaItem(mediaItem!!)
        exoPlayer!!.prepare()
        exoPlayer!!.playWhenReady = autoplay
    }

    fun mute() {
        this.muted = true
        exoPlayer?.volume = 0f
    }

    fun unmute() {
        this.muted = false
        exoPlayer?.volume = 1f
    }

    fun hasAudioTrack(): Boolean {
        exoPlayer?.let {
            return it.currentTracks.containsType(C.TRACK_TYPE_AUDIO)
        }
        return false
    }

    fun isInitialized(): Boolean = exoPlayer != null

    fun setResizeMode(resizeMode: Int) {
        exoView.resizeMode = resizeMode
    }

    val isPlaying: Boolean get() = exoPlayer?.isPlaying ?: false

    fun pause() {
        exoPlayer?.playWhenReady = false
    }

    fun hideControls() {
        exoView.hideController()
    }

    fun setKeepContentOnPlayerReset(keepContentOnPlayerReset: Boolean) {
        exoView.setKeepContentOnPlayerReset(keepContentOnPlayerReset)
    }

    override fun setOnClickListener(l: OnClickListener?) {
        exoView.isClickable = false
        super.setOnClickListener(l)
    }

    fun getControlView(): LegacyPlayerControlView? = this.exoControls

    fun stop() {
        exoPlayer?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    fun cleanup() {
        stop()

        exoPlayer?.let {
            exoView.player = null

            if (it == exoControls.player) {
                exoControls.player = null
            }

            it.removeListener(playerListener)
            it.removeListener(exoPlayerListener)

            ApplicationDependencies.getExoPlayerPool().pool(it)
            this.exoPlayer = null
        }
    }

    fun loopForever() {
        exoPlayer?.repeatMode = Player.REPEAT_MODE_ONE
    }

    fun getDuration(): Long = exoPlayer?.duration ?: 0L

    var playbackPosition: Long
        get() = exoPlayer?.currentPosition ?: 0L
        set(positionMs) {
            exoPlayer?.seekTo(positionMs)
        }

    val playbackPositionUs: Long
        get() = exoPlayer?.let { TimeUnit.MILLISECONDS.toMicros(it.currentPosition) } ?: -1L

    fun clip(fromUs: Long, toUs: Long, playWhenReady: Boolean) {
        val player = exoPlayer
        val item = mediaItem
        if (player != null && item != null) {
            val mediaItemSource = mediaSourceFactory.createMediaSource(item)
            val clippedSource = ClippingMediaSource(mediaItemSource, fromUs, toUs)

            player.setMediaSource(clippedSource)
            player.prepare()
            player.playWhenReady = playWhenReady
            clipped = true
            clippedStartUs = fromUs
        }
    }

    fun removeClip(playWhenReady: Boolean) {
        val player = exoPlayer
        val item = mediaItem
        if (player != null && item != null) {
            if (clipped) {
                player.setMediaItem(item)
                player.prepare()
                clipped = false
                clippedStartUs = 0
            }
            player.playWhenReady = playWhenReady
        }
    }

    fun setWindow(window: Window?) {
        this.window = window
    }

    fun setPlayerStateCallbacks(playerStateCallback: PlayerStateCallback?) {
        this.playerStateCallback = playerStateCallback
    }

    fun setPlayerCallback(playerCallback: PlayerCallback?) {
        this.playerCallback = playerCallback
    }

    fun setPlayerPositionDiscontinuityCallback(playerPositionDiscontinuityCallback: PlayerPositionDiscontinuityCallback) {
        this.playerPositionDiscontinuityCallback = playerPositionDiscontinuityCallback
    }

    /**
     * Resumes a paused video, or restarts if at end of video.
     */
    fun play() {
        exoPlayer?.let {
            it.playWhenReady = true
            if (it.currentPosition >= it.duration) {
                it.seekTo(0)
            }
        }
    }

    private fun getClippingConfiguration(startMs: Long, endMs: Long): MediaItem.ClippingConfiguration =
        if (startMs != endMs) {
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()
        } else {
            MediaItem.ClippingConfiguration.UNSET
        }

    private inner class ExoPlayerListener : Player.Listener {

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            onPlaybackStateChanged(playWhenReady, exoPlayer!!.playbackState)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            onPlaybackStateChanged(exoPlayer!!.playWhenReady, playbackState)
        }

        private fun onPlaybackStateChanged(playWhenReady: Boolean, playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE, Player.STATE_BUFFERING, Player.STATE_ENDED ->
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Player.STATE_READY -> {
                    if (window != null) {
                        if (playWhenReady) {
                            window!!.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window!!.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                    notifyPlayerReady()
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            playerPositionDiscontinuityCallback?.onPositionDiscontinuity(this@VideoPlayer, reason)
        }

        private fun notifyPlayerReady() {
            playerStateCallback?.onPlayerReady()
        }
    }

    interface PlayerStateCallback {
        fun onPlayerReady()
    }

    interface PlayerPositionDiscontinuityCallback {
        fun onPositionDiscontinuity(player: VideoPlayer, reason: Int)
    }

    interface PlayerCallback {

        fun onReady() {}

        fun onPlaying()

        fun onStopped()

        fun onError()
    }

    companion object {
        private const val TAG = "VideoPlayer"
    }
}
