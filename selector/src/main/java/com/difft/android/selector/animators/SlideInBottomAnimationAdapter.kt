package com.difft.android.selector.animators

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class SlideInBottomAnimationAdapter(adapter: RecyclerView.Adapter<*>) :
    BaseAnimationAdapter(adapter) {

    override fun getAnimators(view: View): Array<Animator> {
        return arrayOf(
            ObjectAnimator.ofFloat(view, "translationY", view.measuredHeight.toFloat(), 0f)
        )
    }
}
