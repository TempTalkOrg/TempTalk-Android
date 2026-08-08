package com.difft.android.selector.animators

import android.view.View
import androidx.core.view.ViewCompat

object ViewHelper {
    @JvmStatic
    fun clear(v: View) {
        v.alpha = 1f
        v.scaleY = 1f
        v.scaleX = 1f
        v.translationY = 0f
        v.translationX = 0f
        v.rotation = 0f
        v.rotationY = 0f
        v.rotationX = 0f
        v.pivotY = (v.measuredHeight / 2).toFloat()
        v.pivotX = (v.measuredWidth / 2).toFloat()
        ViewCompat.animate(v).setInterpolator(null).setStartDelay(0L)
    }
}
