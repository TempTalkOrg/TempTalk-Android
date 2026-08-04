package com.difft.android.imageeditor.core.model

import android.animation.ValueAnimator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator

internal class AlphaAnimation private constructor(
    private val from: Float,
    private val to: Float,
    private val invalidate: Runnable?
) {

    private val canAnimate: Boolean = invalidate != null
    private var animatedFraction = 0f

    private constructor(fixed: Float) : this(fixed, fixed, null)

    private fun start() {
        if (canAnimate && invalidate != null) {
            val animator = ValueAnimator.ofFloat(from, to)
            animator.duration = 200
            animator.interpolator = interpolator
            animator.addUpdateListener { animation ->
                animatedFraction = animation.animatedValue as Float
                invalidate.run()
            }
            animator.start()
        }
    }

    fun getValue(): Float {
        if (!canAnimate) return to

        return animatedFraction
    }

    companion object {
        private val interpolator: Interpolator = LinearInterpolator()

        val NULL_1 = AlphaAnimation(1f)

        fun animate(from: Float, to: Float, invalidate: Runnable?): AlphaAnimation {
            if (invalidate == null) {
                return AlphaAnimation(to)
            }

            return if (from != to) {
                val animationMatrix = AlphaAnimation(from, to, invalidate)
                animationMatrix.start()
                animationMatrix
            } else {
                AlphaAnimation(to)
            }
        }
    }
}
