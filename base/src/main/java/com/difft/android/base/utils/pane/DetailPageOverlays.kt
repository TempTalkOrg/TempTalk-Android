package com.difft.android.base.utils.pane

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager

/**
 * Runs [dismiss] whenever this fragment's detail page stops being the visible top (covered,
 * hidden with its tab, or popped). For overlays a page attaches to the Activity root (message
 * action menus): they would otherwise stay over whatever page is shown next and keep their own
 * BackHandler. Outside a detail stack nothing is registered, so shells and popups keep today's
 * behaviour. Call once per view, from `onViewCreated` or later.
 */
fun Fragment.dismissWhenLeavingVisibleTop(dismiss: () -> Unit) {
    if (!isInDetailStack()) return
    detailBackOwner().lifecycle.addObserver(
        LifecycleEventObserver { _, event ->
            // A started owner always passes ON_STOP on its way to DESTROYED.
            if (event == Lifecycle.Event.ON_STOP) dismiss()
        }
    )
}

/**
 * Dismisses [dialog] when this page's view is destroyed (popped, its stack cleared or replaced,
 * recreated). [ComposeDialogManager] mounts dialogs on the Activity, which for a detail page is
 * the home screen and outlives the page, so a dialog left up would run its callbacks against a
 * detached page. Not tied to ON_STOP: the dialog stays across a picker Activity or backgrounding.
 *
 * Without a view (shown from an async callback before or after it) the page itself is the bound,
 * as in [PageWait]; a page no longer added (its callback outlived it) dismisses the dialog at
 * once. A dialog the user has already closed is left alone, so its close callbacks are not run
 * again. Same in a shell
 * Activity. Returns [dialog], to wrap the show call. Main thread.
 */
fun <T : ComposeDialog?> Fragment.dismissWithView(dialog: T): T {
    if (dialog == null) return dialog
    val viewOwner = viewLifecycleOwnerLiveData.value
    val owner: LifecycleOwner = viewOwner ?: this
    // A removed page's own lifecycle is reset to INITIALIZED, never DESTROYED: test isAdded.
    val pageGone = viewOwner == null && !isAdded
    if (pageGone || owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
        if (dialog.isShowing) dialog.dismiss()
        return dialog
    }
    owner.lifecycle.addObserver(object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            if (event != Lifecycle.Event.ON_DESTROY) return
            source.lifecycle.removeObserver(this)
            if (dialog.isShowing) dialog.dismiss()
        }
    })
    return dialog
}
