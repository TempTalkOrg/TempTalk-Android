package com.difft.android.selector.adapter.holder

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.difft.android.selector.R
import com.difft.android.selector.config.PictureConfig
import com.difft.android.selector.engine.MediaPlayerEngine
import com.difft.android.selector.engine.VideoPlayerEngine
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnPlayerListener
import com.difft.android.selector.utils.IntentUtils

class PreviewVideoHolder(itemView: View) : BasePreviewHolder(itemView) {
    @JvmField
    var ivPlayButton: ImageView? = null
    @JvmField
    var progress: ProgressBar? = null
    @JvmField
    var videoPlayer: View? = null
    private var isPlayed = false

    init {
        ivPlayButton = itemView.findViewById(R.id.iv_play_video)
        progress = itemView.findViewById(R.id.progress)
        // Hide custom play button since ExoPlayer has built-in controls
        ivPlayButton!!.visibility = View.GONE
        if (selectorConfig.videoPlayerEngine == null) {
            selectorConfig.videoPlayerEngine = MediaPlayerEngine()
        }
        videoPlayer = selectorConfig.videoPlayerEngine?.onCreateVideoPlayer(itemView.context)
        if (videoPlayer == null) {
            throw NullPointerException("onCreateVideoPlayer cannot be empty,Please implement " + VideoPlayerEngine::class.java)
        }
        if (videoPlayer!!.layoutParams == null) {
            videoPlayer!!.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val viewGroup = itemView as ViewGroup
        if (viewGroup.indexOfChild(videoPlayer) != -1) {
            viewGroup.removeView(videoPlayer)
        }
        viewGroup.addView(videoPlayer, 0)
        videoPlayer!!.visibility = View.GONE
    }

    @Suppress("UNCHECKED_CAST")
    private fun videoEngine(): VideoPlayerEngine<View>? =
        selectorConfig.videoPlayerEngine as VideoPlayerEngine<View>?

    override fun findViews(itemView: View) {
    }

    override fun loadImage(media: LocalMedia, maxWidth: Int, maxHeight: Int) {
        val engine = selectorConfig.imageEngine
        if (engine != null) {
            val availablePath = media.availablePath
            if (maxWidth == PictureConfig.UNSET && maxHeight == PictureConfig.UNSET) {
                engine.loadImage(itemView.context, availablePath, coverImageView!!)
            } else {
                engine.loadImage(itemView.context, coverImageView!!, availablePath, maxWidth, maxHeight)
            }
        }
    }

    override fun onClickBackPressed() {
        coverImageView!!.setOnViewTapListener { _, _, _ ->
            mPreviewEventListener?.onBackPressed()
        }
    }

    override fun onLongPressDownload(media: LocalMedia) {
        coverImageView!!.setOnLongClickListener {
            mPreviewEventListener?.onLongPressDownload(media)
            false
        }
    }

    override fun bindData(media: LocalMedia, position: Int) {
        super.bindData(media, position)
        setScaleDisplaySize(media)
        ivPlayButton!!.setOnClickListener {
            if (selectorConfig.isPauseResumePlay) {
                dispatchPlay()
            } else {
                startPlay()
            }
        }
        itemView.setOnClickListener {
            if (selectorConfig.isPauseResumePlay) {
                dispatchPlay()
            } else {
                mPreviewEventListener?.onBackPressed()
            }
        }
    }

    private fun dispatchPlay() {
        if (isPlayed) {
            if (isPlaying()) {
                onPause()
            } else {
                onResume()
            }
        } else {
            startPlay()
        }
    }

    private fun onResume() {
        ivPlayButton!!.visibility = View.GONE
        videoEngine()?.onResume(videoPlayer!!)
    }

    fun onPause() {
        // Keep play button hidden since ExoPlayer has built-in controls
        videoEngine()?.onPause(videoPlayer!!)
    }

    override fun isPlaying(): Boolean {
        val engine = videoEngine()
        return engine != null && engine.isPlaying(videoPlayer!!)
    }

    private val mPlayerListener = object : OnPlayerListener {
        override fun onPlayerError() {
            playerDefaultUI()
        }

        override fun onPlayerReady() {
            playerIngUI()
        }

        override fun onPlayerLoading() {
            progress!!.visibility = View.VISIBLE
        }

        override fun onPlayerEnd() {
            // Don't hide PlayerView on video end - keep it visible so user can:
            // 1. Tap to show/hide controls
            // 2. Replay via the control bar
            // playerDefaultUI() will be called when view is detached
        }

        override fun onPlayerTap() {
            // Handle tap on video player to toggle UI visibility
            mPreviewEventListener?.onBackPressed()
        }

        override fun onPlayerLongPress() {
            // Handle long press on video player for save/download
            val l = mPreviewEventListener
            val m = media
            if (l != null && m != null) {
                l.onLongPressDownload(m)
            }
        }
    }

    fun startPlay() {
        if (selectorConfig.isUseSystemVideoPlayer) {
            IntentUtils.startSystemPlayerVideo(itemView.context, media!!.availablePath)
        } else {
            if (videoPlayer == null) {
                throw NullPointerException("VideoPlayer cannot be empty,Please implement " + VideoPlayerEngine::class.java)
            }
            val engine = videoEngine()
            if (engine != null) {
                progress!!.visibility = View.VISIBLE
                ivPlayButton!!.visibility = View.GONE
                mPreviewEventListener!!.onPreviewVideoTitle(media!!.fileName)
                isPlayed = true
                engine.onStarPlayer(videoPlayer!!, media!!)
            }
        }
    }

    override fun setScaleDisplaySize(media: LocalMedia) {
        super.setScaleDisplaySize(media)
        if (!selectorConfig.isPreviewZoomEffect && screenWidth < screenHeight) {
            val layoutParams = videoPlayer!!.layoutParams
            if (layoutParams is FrameLayout.LayoutParams) {
                layoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
                layoutParams.gravity = Gravity.CENTER
            } else if (layoutParams is RelativeLayout.LayoutParams) {
                layoutParams.width = RelativeLayout.LayoutParams.MATCH_PARENT
                layoutParams.height = RelativeLayout.LayoutParams.MATCH_PARENT
                layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT)
            } else if (layoutParams is LinearLayout.LayoutParams) {
                layoutParams.width = LinearLayout.LayoutParams.MATCH_PARENT
                layoutParams.height = LinearLayout.LayoutParams.MATCH_PARENT
                layoutParams.gravity = Gravity.CENTER
            } else if (layoutParams is ConstraintLayout.LayoutParams) {
                layoutParams.width = ConstraintLayout.LayoutParams.MATCH_PARENT
                layoutParams.height = ConstraintLayout.LayoutParams.MATCH_PARENT
                layoutParams.topToTop = ConstraintSet.PARENT_ID
                layoutParams.bottomToBottom = ConstraintSet.PARENT_ID
            }
        }
    }

    private fun playerDefaultUI() {
        isPlayed = false
        // Keep play button hidden since ExoPlayer has built-in controls
        progress!!.visibility = View.GONE
        coverImageView!!.visibility = View.VISIBLE
        videoPlayer!!.visibility = View.GONE
        mPreviewEventListener?.onPreviewVideoTitle(null)
    }

    private fun playerIngUI() {
        progress!!.visibility = View.GONE
        ivPlayButton!!.visibility = View.GONE
        coverImageView!!.visibility = View.GONE
        videoPlayer!!.visibility = View.VISIBLE
    }

    override fun onViewAttachedToWindow() {
        val engine = videoEngine()
        if (engine != null) {
            engine.onPlayerAttachedToWindow(videoPlayer!!)
            engine.addPlayListener(mPlayerListener)
        }
    }

    override fun onViewDetachedFromWindow() {
        val engine = videoEngine()
        if (engine != null) {
            engine.onPlayerDetachedFromWindow(videoPlayer!!)
            engine.removePlayListener(mPlayerListener)
        }
        playerDefaultUI()
    }

    override fun resumePausePlay() {
        if (isPlaying()) {
            onPause()
        } else {
            onResume()
        }
    }

    override fun release() {
        val engine = videoEngine()
        if (engine != null) {
            engine.removePlayListener(mPlayerListener)
            engine.destroy(videoPlayer!!)
        }
    }
}
