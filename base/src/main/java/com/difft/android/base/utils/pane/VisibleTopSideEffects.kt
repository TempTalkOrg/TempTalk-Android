package com.difft.android.base.utils.pane

import androidx.core.view.doOnNextLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Whether a side effect that depends on what the user sees (read receipts, "seen" marks) may run
 * now: the view exists and, in a detail stack, this fragment's page is the visible top of the
 * current tab (not covered by a pushed page, not in a hidden tab, app in front). Outside a stack
 * (shell, popup) only the view is required, as before. Check it when the effect runs, so work
 * queued before the page was covered is covered too.
 */
fun Fragment.isVisibleTopNow(): Boolean {
    if (view == null) return false
    if (!isInDetailStack()) return true
    return detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

/**
 * In a detail stack, runs [action] once after the next layout each time this page becomes the
 * visible top (first shown, revealed by a pop, its tab shown, the app back in front), so side
 * effects skipped by [isVisibleTopNow] catch up on what is now shown. That includes a view
 * created while covered or hidden (restored under a pushed page or in a hidden tab after a fold):
 * its first visible-top start is its reveal. Nothing outside a stack. Call once per view, from
 * `onViewCreated` (the owner is not started yet then, so no start is missed).
 */
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
