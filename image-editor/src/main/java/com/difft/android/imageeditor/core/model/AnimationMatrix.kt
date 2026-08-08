package com.difft.android.imageeditor.core.model

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.view.animation.CycleInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import com.difft.android.imageeditor.core.CanvasMatrix

/**
 * Animation Matrix provides a matrix that animates over time down to the identity matrix.
 */
internal class AnimationMatrix {

    private val invalidate: Runnable?
    private val canAnimate: Boolean
    private val undoValues = FloatArray(9)

    private val temp = Matrix()
    private val tempValues = FloatArray(9)

    private var animator: ValueAnimator? = null
    private var animatedFraction = 0f

    private constructor(undo: Matrix, invalidate: Runnable) {
        this.invalidate = invalidate
        this.canAnimate = true
        undo.getValues(undoValues)
    }

    private constructor() {
        canAnimate = false
        invalidate = null
    }

    private fun start(interpolator: Interpolator) {
        if (canAnimate) {
            val animator = ValueAnimator.ofFloat(1f, 0f)
            this.animator = animator
            animator.duration = 250
            animator.interpolator = interpolator
            animator.addUpdateListener { animation ->
                animatedFraction = animation.animatedValue as Float
                invalidate?.run()
            }
            animator.start()
        }
    }

    fun stop() {
        animator?.cancel()
    }

    /**
     * Append the current animation value.
     */
    fun preConcatValueTo(onTo: Matrix) {
        if (!canAnimate) return

        onTo.preConcat(buildTemp())
    }

    /**
     * Append the current animation value.
     */
    fun preConcatValueTo(canvasMatrix: CanvasMatrix) {
        if (!canAnimate) return

        canvasMatrix.concat(buildTemp())
    }

    private fun buildTemp(): Matrix {
        if (!canAnimate) {
            temp.reset()
            return temp
        }

        val fractionCompliment = 1f - animatedFraction
        for (i in 0 until 9) {
            tempValues[i] = fractionCompliment * iValues[i] + animatedFraction * undoValues[i]
        }

        temp.setValues(tempValues)
        return temp
    }

    companion object {
        private val iValues = FloatArray(9)
        private val interpolator: Interpolator = DecelerateInterpolator()
        private val pulseInterpolator: Interpolator = inverse(CycleInterpolator(0.5f))

        var NULL = AnimationMatrix()

        init {
            Matrix().getValues(iValues)
        }

        fun animate(from: Matrix, to: Matrix, invalidate: Runnable?): AnimationMatrix {
            if (invalidate == null) {
                return NULL
            }

            val undo = Matrix()
            val inverted = to.invert(undo)
            if (inverted) {
                undo.preConcat(from)
            }
            return if (inverted && !undo.isIdentity) {
                val animationMatrix = AnimationMatrix(undo, invalidate)
                animationMatrix.start(interpolator)
                animationMatrix
            } else {
                NULL
            }
        }

        /**
         * Animate applying a matrix and then animate removing.
         */
        fun singlePulse(pulse: Matrix, invalidate: Runnable?): AnimationMatrix {
            if (invalidate == null) {
                return NULL
            }

            val animationMatrix = AnimationMatrix(pulse, invalidate)
            animationMatrix.start(pulseInterpolator)

            return animationMatrix
        }

        private fun inverse(interpolator: Interpolator): Interpolator =
            Interpolator { input -> 1f - interpolator.getInterpolation(input) }
    }
}
