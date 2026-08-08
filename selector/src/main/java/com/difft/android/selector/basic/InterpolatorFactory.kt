package com.difft.android.selector.basic

import android.view.animation.Interpolator

interface InterpolatorFactory {
    /**
     * An interpolator defines the rate of change of an animation.
     * This allows the basic animation effects (alpha, scale, translate, rotate)
     * to be accelerated, decelerated, repeated, etc.
     */
    fun newInterpolator(): Interpolator
}
