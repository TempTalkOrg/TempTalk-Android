package com.difft.android.base.utils.pane

import android.content.Context
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Keyboard policy for a page with text input pushed onto a detail stack (shells keep their
 * manifest `windowSoftInputMode`, so this does nothing there):
 * - [showOnFirstOpen]: [focus] runs on the first `onResume` of a page created without saved
 *   state (not after a fold recreation), e.g. `focusAndShowKeyboard()`;
 * - when the page stops being the visible top (covered, popped, tab hidden) the keyboard is hidden
 *   if the page holds focus, so it does not stay over the page shown next;
 * - below API 30 the page holds [SoftInputModeLease] while it is the visible top.
 * Call once per view, from `onViewCreated`.
 */
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

/**
 * In a detail stack, hides the keyboard of this page's focused field now, before it navigates
 * away, so the keyboard starts closing before the next page is laid out (that page is in the same
 * window). Shells keep their behaviour.
 */
fun Fragment.hideDetailKeyboardBeforeLeaving() {
    if (isInDetailStack()) hideKeyboardIfFocused()
}

private fun Fragment.hideKeyboardIfFocused() {
    val focused = view?.findFocus() ?: return
    val imm = focused.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    imm.hideSoftInputFromWindow(focused.windowToken, 0)
}
