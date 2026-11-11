package com.difft.android.base.ui.topchrome

import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.updatePadding
import com.difft.android.base.R
import com.difft.android.base.utils.safeTopInset










enum class TopChromeStyle { Band, Feather, Opaque }


interface TopChromeOwner {
    val topChromeStyle: TopChromeStyle
}





fun View.topChromeInset(): Int = safeTopInset()






object TopChromeHost {

    fun adopt(page: Any, view: View) {
        if ((page as? TopChromeOwner)?.topChromeStyle?.let { it != TopChromeStyle.Opaque } == true) return
        installTopInsetPadding(view)
    }
}





fun installTopInsetPadding(view: View) {
    val base = view.paddingTop
    TopInsetFollower.install(view, { safeTopInset() }) { view.updatePadding(top = base + it) }
}





fun installTopInsetSpacer(spacer: View) {
    TopInsetFollower.install(spacer, { topChromeInset() }) { inset ->
        spacer.layoutParams = spacer.layoutParams.also { it.height = inset }
    }
}


private class TopInsetFollower private constructor(
    private val view: View,
    private val inset: View.() -> Int,
    private val apply: (Int) -> Unit,
) :
    View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private var appliedInset = -1
    private var observer: ViewTreeObserver? = null

    override fun onViewAttachedToWindow(v: View) {
        if (observer == null) observer = view.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        sync()
    }

    override fun onViewDetachedFromWindow(v: View) {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
    }

    override fun onPreDraw(): Boolean = !sync()

    
    private fun sync(): Boolean {
        val current = view.inset()
        if (current == appliedInset) return false
        appliedInset = current
        apply(current)
        return true
    }

    companion object {
        fun install(view: View, inset: View.() -> Int, apply: (Int) -> Unit) {
            if (view.getTag(R.id.top_chrome_inset) != null) return
            val follower = TopInsetFollower(view, inset, apply)
            view.setTag(R.id.top_chrome_inset, follower)
            view.addOnAttachStateChangeListener(follower)
            if (view.isAttachedToWindow) follower.onViewAttachedToWindow(view)
        }
    }
}
