package com.difft.android.selector.engine

import android.content.Context
import android.view.View

import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnPlayerListener

interface VideoPlayerEngine<T> {
    
    fun onCreateVideoPlayer(context: Context): View

    
    fun onStarPlayer(player: T, media: LocalMedia)

    fun onResume(player: T)

    fun onPause(player: T)

    
    fun isPlaying(player: T): Boolean

    fun addPlayListener(playerListener: OnPlayerListener)

    fun removePlayListener(playerListener: OnPlayerListener?)

    
    fun onPlayerAttachedToWindow(player: T)

    
    fun onPlayerDetachedFromWindow(player: T)

    
    fun destroy(player: T)
}
