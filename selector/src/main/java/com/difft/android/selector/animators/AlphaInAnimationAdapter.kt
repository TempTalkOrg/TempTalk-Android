package com.difft.android.selector.animators

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class AlphaInAnimationAdapter : BaseAnimationAdapter {

    private val mFrom: Float

    constructor(adapter: RecyclerView.Adapter<*>) : this(adapter, DEFAULT_ALPHA_FROM)

    constructor(adapter: RecyclerView.Adapter<*>, from: Float) : super(adapter) {
        mFrom = from
    }

    override fun getAnimators(view: View): Array<Animator> {
        return arrayOf(ObjectAnimator.ofFloat(view, "alpha", mFrom, 1f))
    }

    companion object {
        private const val DEFAULT_ALPHA_FROM = 0f
    }
}
