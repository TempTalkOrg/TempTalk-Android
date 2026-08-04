package com.difft.android.chat.animation

import android.view.View
import android.view.animation.Animation
import android.view.animation.Transformation

class ResizeAnimation(
    private val target: View,
    private val targetWidthPx: Int,
    private val targetHeightPx: Int
) : Animation() {

    private var startWidth = 0
    private var startHeight = 0

    override fun applyTransformation(interpolatedTime: Float, t: Transformation?) {
        val newWidth = (startWidth + (targetWidthPx - startWidth) * interpolatedTime).toInt()
        val newHeight = (startHeight + (targetHeightPx - startHeight) * interpolatedTime).toInt()

        val params = target.layoutParams
        params.width = newWidth
        params.height = newHeight
        target.layoutParams = params
    }

    override fun initialize(width: Int, height: Int, parentWidth: Int, parentHeight: Int) {
        super.initialize(width, height, parentWidth, parentHeight)
        startWidth = width
        startHeight = height
    }

    override fun willChangeBounds(): Boolean = true
}
