package com.difft.android.selector.engine

import android.content.Context
import android.view.View

import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnPlayerListener

interface VideoPlayerEngine<T> {
    /** Create player instance. */
    fun onCreateVideoPlayer(context: Context): View

    /** Start playing video. */
    fun onStarPlayer(player: T, media: LocalMedia)

    fun onResume(player: T)

    fun onPause(player: T)

    /** Video playing status. */
    fun isPlaying(player: T): Boolean

    fun addPlayListener(playerListener: OnPlayerListener)

    fun removePlayListener(playerListener: OnPlayerListener?)

    /** Player attached to window. */
    fun onPlayerAttachedToWindow(player: T)

    /** Player detached from window. */
    fun onPlayerDetachedFromWindow(player: T)

    /** Destroy / release player. */
    fun destroy(player: T)
}
