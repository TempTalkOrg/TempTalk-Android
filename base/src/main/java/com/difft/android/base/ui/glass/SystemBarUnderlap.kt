package com.difft.android.base.ui.glass

import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner








fun trackSystemBarUnderlap(owner: LifecycleOwner, viewport: View, onChange: (Insets) -> Boolean) {
    if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
    val position = IntArray(2)
    val preDraw = ViewTreeObserver.OnPreDrawListener {
        viewport.getLocationInWindow(position)
        val windowInsets = ViewCompat.getRootWindowInsets(viewport)
        val bars = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: Insets.NONE
        val nav = windowInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        onChange(systemBarUnderlapInsets(bars.top, position[1], nav, viewport.paddingBottom))
    }
    val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnPreDrawListener(preDraw)
        }

        override fun onViewDetachedFromWindow(v: View) {
            v.viewTreeObserver.removeOnPreDrawListener(preDraw)
        }
    }
    viewport.addOnAttachStateChangeListener(attachment)
    if (viewport.isAttachedToWindow) attachment.onViewAttachedToWindow(viewport)
    owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) {
            viewport.removeOnAttachStateChangeListener(attachment)
            if (viewport.isAttachedToWindow) viewport.viewTreeObserver.removeOnPreDrawListener(preDraw)
        }
    })
}

internal fun systemBarUnderlapInsets(systemTop: Int, viewportY: Int, navigationBottom: Int, reservedBottom: Int): Insets =
    Insets.of(0, minOf(systemTop.coerceAtLeast(0), viewportY.coerceAtLeast(0)), 0,
        minOf(navigationBottom.coerceAtLeast(0), reservedBottom.coerceAtLeast(0)))
