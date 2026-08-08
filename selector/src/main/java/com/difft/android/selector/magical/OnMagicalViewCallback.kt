package com.difft.android.selector.magical

interface OnMagicalViewCallback {

    fun onBeginBackMinAnim()

    fun onBeginBackMinMagicalFinish(isResetSize: Boolean)

    fun onBeginMagicalAnimComplete(mojitoView: MagicalView, showImmediately: Boolean)

    fun onBackgroundAlpha(alpha: Float)

    fun onMagicalViewFinish()
}
