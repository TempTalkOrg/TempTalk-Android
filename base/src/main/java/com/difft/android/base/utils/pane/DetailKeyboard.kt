package com.difft.android.base.utils.pane

import android.content.Context
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver











fun Fragment.detailKeyboard(showOnFirstOpen: Boolean, focus: () -> Unit, savedInstanceState: Bundle?) {
    if (!isInDetailStack()) return
    val page = this
    val activity = requireActivity()
    var showPending = showOnFirstOpen && savedInstanceState == null
    viewLifecycleOwner.lifecycle.addObserver(
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && showPending) {
                showPending = false
                focus()
            }
        }
    )
    detailBackOwner().lifecycle.addObserver(
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> SoftInputModeLease.acquireResize(activity, page)
                Lifecycle.Event.ON_STOP -> {
                    hideKeyboardIfFocused()
                    SoftInputModeLease.release(activity, page)
                }
                Lifecycle.Event.ON_DESTROY -> SoftInputModeLease.release(activity, page)
                else -> Unit
            }
        }
    )
}






fun Fragment.hideDetailKeyboardBeforeLeaving() {
    if (isInDetailStack()) hideKeyboardIfFocused()
}

private fun Fragment.hideKeyboardIfFocused() {
    val focused = view?.findFocus() ?: return
    val imm = focused.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    imm.hideSoftInputFromWindow(focused.windowToken, 0)
}
