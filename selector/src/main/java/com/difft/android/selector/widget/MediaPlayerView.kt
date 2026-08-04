package com.difft.android.selector.widget

import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.config.PictureMimeType
import java.io.IOException

class MediaPlayerView : FrameLayout, SurfaceHolder.Callback {
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var surfaceView: VideoSurfaceView

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) {
        init()
    }

    private fun init() {
        surfaceView = VideoSurfaceView(context)
        val layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        layoutParams.gravity = Gravity.CENTER
        surfaceView.layoutParams = layoutParams

        addView(surfaceView)
        val surfaceHolder = surfaceView.holder
        surfaceHolder.setFormat(PixelFormat.TRANSPARENT)
        surfaceHolder.addCallback(this)
    }

    fun initMediaPlayer(): MediaPlayer {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer()
        }
        mediaPlayer!!.setOnVideoSizeChangedListener { mp, _, _ ->
            surfaceView.adjustVideoSize(mp.videoWidth, mp.videoHeight)
        }
        return mediaPlayer!!
    }

    fun getMediaPlayer(): MediaPlayer? = mediaPlayer

    fun getSurfaceView(): VideoSurfaceView = surfaceView

    fun start(path: String) {
        try {
            if (PictureMimeType.isContent(path)) {
                mediaPlayer!!.setDataSource(context, Uri.parse(path))
            } else {
                mediaPlayer!!.setDataSource(path)
            }
            mediaPlayer!!.prepareAsync()
        } catch (e: IOException) {
            L.w(e) { "[MediaPlayerView] start error:" }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        mediaPlayer!!.setAudioStreamType(AudioManager.STREAM_MUSIC)
        mediaPlayer!!.setDisplay(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
    }

    fun clearCanvas() {
        surfaceView.holder.setFormat(PixelFormat.OPAQUE)
        surfaceView.holder.setFormat(PixelFormat.TRANSPARENT)
    }

    class VideoSurfaceView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : SurfaceView(context, attrs, defStyleAttr) {
        private var videoWidth = 0
        private var videoHeight = 0

        fun adjustVideoSize(videoWidth: Int, videoHeight: Int) {
            if (videoWidth == 0 || videoHeight == 0) {
                return
            }
            this.videoWidth = videoWidth
            this.videoHeight = videoHeight
            holder.setFixedSize(videoWidth, videoHeight)
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var width = View.getDefaultSize(videoWidth, widthMeasureSpec)
            var height = View.getDefaultSize(videoHeight, heightMeasureSpec)
            if (videoWidth > 0 && videoHeight > 0) {
                val widthSpecMode = View.MeasureSpec.getMode(widthMeasureSpec)
                val widthSpecSize = View.MeasureSpec.getSize(widthMeasureSpec)
                val heightSpecMode = View.MeasureSpec.getMode(heightMeasureSpec)
                val heightSpecSize = View.MeasureSpec.getSize(heightMeasureSpec)
                if (widthSpecMode == View.MeasureSpec.EXACTLY && heightSpecMode == View.MeasureSpec.EXACTLY) {
                    width = widthSpecSize
                    height = heightSpecSize
                    if (videoWidth * height < width * videoHeight) {
                        width = height * videoWidth / videoHeight
                    } else if (videoWidth * height > width * videoHeight) {
                        height = width * videoHeight / videoWidth
                    }
                } else if (widthSpecMode == View.MeasureSpec.EXACTLY) {
                    width = widthSpecSize
                    height = width * videoHeight / videoWidth
                    if (heightSpecMode == View.MeasureSpec.AT_MOST && height > heightSpecSize) {
                        height = heightSpecSize
                    }
                } else if (heightSpecMode == View.MeasureSpec.EXACTLY) {
                    height = heightSpecSize
                    width = height * videoWidth / videoHeight
                    if (widthSpecMode == View.MeasureSpec.AT_MOST && width > widthSpecSize) {
                        width = widthSpecSize
                    }
                } else {
                    width = videoWidth
                    height = videoHeight
                    if (heightSpecMode == View.MeasureSpec.AT_MOST && height > heightSpecSize) {
                        height = heightSpecSize
                        width = height * videoWidth / videoHeight
                    }
                    if (widthSpecMode == View.MeasureSpec.AT_MOST && width > widthSpecSize) {
                        width = widthSpecSize
                        height = width * videoHeight / videoWidth
                    }
                }
            }
            setMeasuredDimension(width, height)
        }
    }

    fun release() {
        mediaPlayer?.let {
            it.release()
            it.setOnPreparedListener(null)
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            mediaPlayer = null
        }
    }
}
