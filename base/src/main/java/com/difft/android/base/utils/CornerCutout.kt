package com.difft.android.base.utils

import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.doOnAttach
import com.difft.android.base.R








fun View.avoidCornerCutout() {
    if (getTag(R.id.corner_cutout) != null) return
    val follower = CornerCutoutFollower(this)
    setTag(R.id.corner_cutout, follower)
    addOnAttachStateChangeListener(follower)
    if (isAttachedToWindow) follower.onViewAttachedToWindow(this)
}


fun View.avoidCornerCutoutInRow() {
    (parent as? View)?.let { it.avoidCornerCutout(); return }
    doOnAttach { (parent as? View)?.avoidCornerCutout() }
}

private const val MAX_ROW_DP = 96f

private class CornerCutoutFollower(private val view: View) :
    View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    
    
    private var baseLeft = view.paddingLeft
    private var baseRight = view.paddingRight
    private var writtenLeft = baseLeft
    private var writtenRight = baseRight
    private val location = IntArray(2)
    private var observer: ViewTreeObserver? = null
    
    
    private var isBarRow = true

    override fun onViewAttachedToWindow(v: View) {
        if (observer == null) observer = view.viewTreeObserver.also { it.addOnPreDrawListener(this) }
    }

    override fun onViewDetachedFromWindow(v: View) {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
    }

    
    override fun onPreDraw(): Boolean {
        if (view.width <= 0) return true
        view.getLocationInWindow(location)
        if (view.paddingLeft != writtenLeft || view.paddingRight != writtenRight) {
            baseLeft = view.paddingLeft
            baseRight = view.paddingRight
            writtenLeft = baseLeft
            writtenRight = baseRight
        }
        if (writtenLeft == baseLeft && writtenRight == baseRight) {
            isBarRow = view.height <= MAX_ROW_DP * view.resources.displayMetrics.density
        }
        val (start, end) = if (!isBarRow) 0 to 0
        else view.cornerCutoutExtra(location[0], location[1], location[0] + view.width, location[1] + view.height)
        val left = baseLeft + start
        val right = baseRight + end
        if (view.paddingLeft == left && view.paddingRight == right) return true
        view.setPadding(left, view.paddingTop, right, view.paddingBottom)
        writtenLeft = left
        writtenRight = right
        return false
    }
}
