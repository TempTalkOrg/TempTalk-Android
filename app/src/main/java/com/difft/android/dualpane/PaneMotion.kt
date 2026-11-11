package com.difft.android.dualpane

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator












internal object PaneMotion {
    const val DURATION_MS = 300L

    
    const val PARALLAX = 0.30f

    
    const val SCRIM_ALPHA = 0.12f

    private const val SHADOW_DP = 12
    private const val SHADOW_COLOR = 0x33000000

    
    fun interpolator(): TimeInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    
    fun slideDistance(view: View): Float {
        val width = if (view.width > 0) view.width else (view.parent as? View)?.width ?: 0
        return if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -width.toFloat() else width.toFloat()
    }

    
    fun slideIn(over: View): Animator = overAnimator(over, from = 1f, to = 0f)

    
    fun slideOut(over: View): Animator = overAnimator(over, from = 0f, to = 1f)

    
    fun parallaxOut(under: View): Animator = underAnimator(under, from = 0f, to = 1f)

    
    fun parallaxBack(under: View): Animator = underAnimator(under, from = 1f, to = 0f)

    
    fun cover(over: View, under: List<View>): Animator = together(slideIn(over), under.map(::parallaxOut))

    
    fun uncover(over: View, under: List<View>): Animator = together(slideOut(over), under.map(::parallaxBack))

    private fun together(first: Animator, rest: List<Animator>): Animator =
        AnimatorSet().apply { playTogether(listOf(first) + rest) }

    private fun base(): ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = DURATION_MS
        interpolator = interpolator()
    }

    
    private fun overAnimator(over: View, from: Float, to: Float): Animator {
        val distance = slideDistance(over)
        val parent = over.parent as? ViewGroup
        val shadowPx = SHADOW_DP * over.resources.displayMetrics.density
        val rtl = distance < 0
        val shadow = GradientDrawable(
            if (rtl) GradientDrawable.Orientation.RIGHT_LEFT else GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.TRANSPARENT, SHADOW_COLOR),
        )
        fun place(offset: Float) {
            over.translationX = distance * offset
            if (parent == null) return
            if (rtl) {
                val edge = (over.right + over.translationX).toInt()
                shadow.setBounds(edge, over.top, (edge + shadowPx).toInt(), over.bottom)
            } else {
                val edge = (over.left + over.translationX).toInt()
                shadow.setBounds((edge - shadowPx).toInt(), over.top, edge, over.bottom)
            }
        }
        return base().apply {
            addUpdateListener { place(from + (to - from) * (it.animatedValue as Float)) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    place(from)
                    parent?.overlay?.add(shadow)
                }

                
                
                override fun onAnimationEnd(animation: Animator) {
                    parent?.overlay?.remove(shadow)
                    if (!cancelled) place(to)
                }

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                    parent?.overlay?.remove(shadow)
                    over.translationX = 0f
                }

                private var cancelled = false
            })
        }
    }

    
    
    
    private fun underAnimator(under: View, from: Float, to: Float): Animator {
        val distance = slideDistance(under)
        var scrim: ColorDrawable? = null
        fun place(progress: Float) {
            under.translationX = 0f - PARALLAX * distance * progress
            scrim?.alpha = (255 * SCRIM_ALPHA * progress).toInt()
        }
        fun rest() {
            under.translationX = 0f
            if (scrim != null && under.foreground === scrim) under.foreground = null
            scrim = null
        }
        return base().apply {
            addUpdateListener { place(from + (to - from) * (it.animatedValue as Float)) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    
                    if (under.foreground == null) {
                        scrim = ColorDrawable(Color.BLACK).also { under.foreground = it }
                    }
                    place(from)
                }

                override fun onAnimationEnd(animation: Animator) = rest()

                override fun onAnimationCancel(animation: Animator) = rest()
            })
        }
    }
}
