package com.difft.android.selector.interfaces

interface OnPlayerListener {
    fun onPlayerError()

    fun onPlayerReady()

    fun onPlayerLoading()

    fun onPlayerEnd()

    /** Single tap on player view (for toggling UI visibility). */
    fun onPlayerTap() {}

    /** Long press on player view (for save/download actions). */
    fun onPlayerLongPress() {}
}
