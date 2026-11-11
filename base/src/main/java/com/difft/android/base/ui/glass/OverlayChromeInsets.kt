package com.difft.android.base.ui.glass

import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.Insets
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner


data class OverlayChromeInsets(val top: Int, val bottom: Int)


internal fun overlayChromeInsets(
    contentTop: Int,
    contentHeight: Int,
    topChromeBottom: Int?,
    bottomChromeTop: Int?,
    systemTop: Int = 0,
    systemBottom: Int = 0,
): OverlayChromeInsets = OverlayChromeInsets(
    maxOf(0, topChromeBottom?.minus(contentTop) ?: 0, systemTop),
    maxOf(0, bottomChromeTop?.let { contentTop + contentHeight - it } ?: 0, systemBottom),
)











fun trackOverlayChrome(
    owner: LifecycleOwner,
    content: View,
    topChrome: View?,
    bottomChrome: View?,
    systemInsets: () -> Insets = { Insets.NONE },
    onChange: (OverlayChromeInsets) -> Unit,
) {
    if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
    var latest: OverlayChromeInsets? = null
    val contentLocation = IntArray(2)
    val chromeLocation = IntArray(2)
    val listener = ViewTreeObserver.OnPreDrawListener {
        if (content.height == 0 || owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            return@OnPreDrawListener true
        }
        content.getLocationInWindow(contentLocation)
        val contentTop = contentLocation[1]
        val bars = systemInsets()
        val top = if (topChrome != null && topChrome.visibility != View.GONE) {
            topChrome.getLocationInWindow(chromeLocation)
            chromeLocation[1] + topChrome.height
        } else null
        val bottom = if (bottomChrome != null && bottomChrome.visibility != View.GONE) {
            bottomChrome.getLocationInWindow(chromeLocation)
            chromeLocation[1]
        } else null
        val insets = overlayChromeInsets(contentTop, content.height, top, bottom, bars.top, bars.bottom)
        if (insets == latest) return@OnPreDrawListener true
        latest = insets
        onChange(insets)
        false
    }
    val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = v.viewTreeObserver.addOnPreDrawListener(listener)
        override fun onViewDetachedFromWindow(v: View) = v.viewTreeObserver.removeOnPreDrawListener(listener)
    }
    if (content.isAttachedToWindow) content.viewTreeObserver.addOnPreDrawListener(listener)
    content.addOnAttachStateChangeListener(attachment)
    owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) {
            content.removeOnAttachStateChangeListener(attachment)
            if (content.isAttachedToWindow) content.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    })
}
