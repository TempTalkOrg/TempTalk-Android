package com.difft.android.selector.engine

import android.content.Context
import android.view.View
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnPlayerListener
import com.difft.android.selector.widget.MediaPlayerView
import java.util.concurrent.CopyOnWriteArrayList

class MediaPlayerEngine : VideoPlayerEngine<MediaPlayerView> {

    private val listeners = CopyOnWriteArrayList<OnPlayerListener>()

    override fun onCreateVideoPlayer(context: Context): View {
        return MediaPlayerView(context)
    }

    override fun onStarPlayer(player: MediaPlayerView, media: LocalMedia) {
        val availablePath = media.availablePath
        val mediaPlayer = player.getMediaPlayer()
        val surfaceView = player.getSurfaceView()
        surfaceView.setZOrderOnTop(PictureMimeType.isHasHttp(availablePath))
        val config = SelectorProviders.getInstance().selectorConfig
        mediaPlayer!!.isLooping = config.isLoopAutoPlay
        player.start(availablePath)
    }

    override fun onResume(player: MediaPlayerView) {
        player.getMediaPlayer()?.start()
    }

    override fun onPause(player: MediaPlayerView) {
        player.getMediaPlayer()?.pause()
    }

    override fun isPlaying(player: MediaPlayerView): Boolean {
        val mediaPlayer = player.getMediaPlayer()
        return mediaPlayer != null && mediaPlayer.isPlaying
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

    override fun onPlayerAttachedToWindow(player: MediaPlayerView) {
        val mediaPlayer = player.initMediaPlayer()
        mediaPlayer.setOnPreparedListener { mp ->
            mp.start()
            for (i in 0 until listeners.size) {
                listeners[i].onPlayerReady()
            }
        }
        mediaPlayer.setOnCompletionListener { mp ->
            mp.reset()
            for (i in 0 until listeners.size) {
                listeners[i].onPlayerEnd()
            }
            player.clearCanvas()
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            for (i in 0 until listeners.size) {
                listeners[i].onPlayerError()
            }
            false
        }
    }

    override fun onPlayerDetachedFromWindow(player: MediaPlayerView) {
        player.release()
    }

    override fun destroy(player: MediaPlayerView) {
        player.release()
    }
}
