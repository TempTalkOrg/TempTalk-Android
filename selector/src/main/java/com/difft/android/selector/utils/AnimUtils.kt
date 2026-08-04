package com.difft.android.selector.utils

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView

object AnimUtils {
    const val DURATION = 250

    /** Arrow rotate animation. */
    @JvmStatic
    fun rotateArrow(arrow: ImageView, isFlag: Boolean) {
        val srcValue: Float
        val targetValue: Float
        if (isFlag) {
            srcValue = 0F
            targetValue = 180F
        } else {
            srcValue = 180F
            targetValue = 0F
        }
        val objectAnimator = ObjectAnimator.ofFloat(arrow, "rotation", srcValue, targetValue)
        objectAnimator.duration = DURATION.toLong()
        objectAnimator.interpolator = LinearInterpolator()
        objectAnimator.start()
    }

    /** Scale animation. */
    @JvmStatic
    fun selectZoom(view: View) {
        val animatorSet = AnimatorSet()
        val objectAnimatorX = ObjectAnimator.ofFloat(view, "scaleX", 1.0F, 1.05F, 1.0F)
        val objectAnimatorY = ObjectAnimator.ofFloat(view, "scaleY", 1.0F, 1.05F, 1.0F)
        animatorSet.playTogether(objectAnimatorX, objectAnimatorY)
        animatorSet.duration = DURATION.toLong()
        animatorSet.interpolator = LinearInterpolator()
        animatorSet.start()
    }
}
