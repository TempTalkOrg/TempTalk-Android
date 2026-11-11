package com.difft.android.base.utils.pane

import androidx.core.view.doOnNextLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver








fun Fragment.isVisibleTopNow(): Boolean {
    if (view == null) return false
    if (!isInDetailStack()) return true
    return detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}









fun Fragment.onVisibleTopAgain(action: () -> Unit) {
    if (!isInDetailStack()) return
    detailBackOwner().lifecycle.addObserver(
        LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_START) return@LifecycleEventObserver
            view?.let {
                it.doOnNextLayout { action() }
                it.requestLayout()
            }
        }
    )
}
