package com.difft.android.selector.interfaces

import android.view.View

interface OnPlayerListener {
    fun onPlayerError()

    fun onPlayerReady()

    fun onPlayerLoading()

    fun onPlayerEnd()

    
    fun onPlayerTap() {}

    
    fun onPlayerLongPress() {}

    



    fun onPlayerControllerVisibility(player: View, visible: Boolean) {}
}
