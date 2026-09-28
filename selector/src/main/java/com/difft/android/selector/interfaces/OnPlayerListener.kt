package com.difft.android.selector.interfaces

import android.view.View

interface OnPlayerListener {
    fun onPlayerError()

    fun onPlayerReady()

    fun onPlayerLoading()

    fun onPlayerEnd()

    /** Single tap on player view (for toggling UI visibility). */
    fun onPlayerTap() {}

    /** Long press on player view (for save/download actions). */
    fun onPlayerLongPress() {}

    /**
     * [player]'s own control bar became fully visible ([visible] true) or started to go away
     * (false). The engine is shared by every page, so receivers match [player] against their own.
     */
    fun onPlayerControllerVisibility(player: View, visible: Boolean) {}
}
