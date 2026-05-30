package com.difft.android.chat.util

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.PixelFormat
import android.view.*
import android.widget.ImageView
import com.difft.android.chat.R
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

object FloatingYouTubeManager {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var playerView: YouTubePlayerView? = null
    
    @SuppressLint("ClickableViewAccessibility")
    fun showFloatingPlayer(activity: Activity, videoId: String, startTime: Float = 0f) {
        if (floatingView != null) {
            hideFloatingPlayer()
        }

        windowManager = activity.getSystemService(Activity.WINDOW_SERVICE) as WindowManager
        
        val inflater = LayoutInflater.from(activity)
        floatingView = inflater.inflate(R.layout.layout_floating_youtube_player, null)
        
        playerView = floatingView!!.findViewById(R.id.youtube_player_view)
        val closeButton = floatingView!!.findViewById<ImageView>(R.id.iv_close)
        
        closeButton.setOnClickListener {
            hideFloatingPlayer()
        }

        val width = (activity.resources.displayMetrics.widthPixels * 0.7).toInt()
        val height = (width * 9 / 16)

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        
        params.gravity = Gravity.TOP or Gravity.START
        params.x = activity.resources.displayMetrics.widthPixels - width - 20
        params.y = 200

        windowManager?.addView(floatingView, params)
        
        val options = IFramePlayerOptions.Builder()
            .controls(1)
            .origin("https://${activity.packageName}")
            .build()

        playerView?.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                youTubePlayer.loadVideo(videoId, startTime)
            }
        }, options)

        // Dragging logic
        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0.0f
            private var initialTouchY: Float = 0.0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        
                        // Simple boundary check
                        val displayWidth = activity.resources.displayMetrics.widthPixels
                        val displayHeight = activity.resources.displayMetrics.heightPixels
                        
                        params.x = maxOf(0, minOf(params.x, displayWidth - width))
                        params.y = maxOf(0, minOf(params.y, displayHeight - height))
                        
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    fun hideFloatingPlayer() {
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {
                // Ignore if already removed
            }
            floatingView = null
            playerView = null
            windowManager = null
        }
    }
}
