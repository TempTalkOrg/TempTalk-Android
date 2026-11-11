package com.difft.android.dualpane

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt









internal object SearchReveal {
    private const val OPEN_MS = 520L
    private const val CLOSE_MS = 500L
    private const val FADE_FROM = 0.78f

    
    fun enabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    fun open(layer: View, origin: Rect): Animator = reveal(layer, origin, opening = true)

    fun close(layer: View, origin: Rect): Animator = reveal(layer, origin, opening = false)

    
    internal fun radiusAt(fraction: Float, buttonRadius: Float, full: Float): Float =
        buttonRadius + (full - buttonRadius) * fraction

    
    internal fun closingAlpha(progress: Float): Float =
        if (progress <= FADE_FROM) 1f else (1f - (progress - FADE_FROM) / (1f - FADE_FROM)).coerceIn(0f, 1f)

    
    internal fun coveringRadius(cx: Float, cy: Float, width: Int, height: Int): Float =
        hypot(max(cx, width - cx), max(cy, height - cy))

    private fun reveal(layer: View, origin: Rect, opening: Boolean): Animator {
        val clip = CircleClip(layer, origin)
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (opening) OPEN_MS else CLOSE_MS
            interpolator = PathInterpolator(0.42f, 0f, 0.32f, 1f)
            addUpdateListener {
                val progress = it.animatedValue as Float
                clip.update(if (opening) progress else 1f - progress)
                if (!opening) layer.alpha = closingAlpha(progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    clip.attach(if (opening) 0f else 1f)
                }

                override fun onAnimationEnd(animation: Animator) {
                    clip.detach()
                    layer.alpha = 1f
                }
            })
        }
    }

    
    private class CircleClip(private val layer: View, private val origin: Rect) : ViewOutlineProvider() {
        private var fraction = 0f
        private var savedProvider: ViewOutlineProvider? = null
        private var savedClip = false

        fun attach(fraction: Float) {
            savedProvider = layer.outlineProvider
            savedClip = layer.clipToOutline
            this.fraction = fraction
            layer.outlineProvider = this
            layer.clipToOutline = true
            layer.invalidateOutline()
        }

        fun update(fraction: Float) {
            this.fraction = fraction
            layer.invalidateOutline()
        }

        fun detach() {
            if (layer.outlineProvider !== this) return
            layer.outlineProvider = savedProvider
            layer.clipToOutline = savedClip
            layer.invalidateOutline()
        }

        override fun getOutline(view: View, outline: Outline) {
            
            val at = IntArray(2).also(view::getLocationInWindow)
            val cx = origin.exactCenterX() - at[0]
            val cy = origin.exactCenterY() - at[1]
            val width = view.width.takeIf { it > 0 } ?: (view.parent as? View)?.width ?: 0
            val height = view.height.takeIf { it > 0 } ?: (view.parent as? View)?.height ?: 0
            val r = radiusAt(fraction, origin.width() / 2f, coveringRadius(cx, cy, width, height))
            outline.setOval((cx - r).roundToInt(), (cy - r).roundToInt(), (cx + r).roundToInt(), (cy + r).roundToInt())
        }
    }
}
