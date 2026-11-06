package com.difft.android.base.utils.pane

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.widget.ComposeDialogManager

/**
 * A page's claim on the shared, non-cancelable wait dialog. The dialog is mounted on the Activity,
 * which for a detail page is the home screen: a page that is closed while its request is in
 * flight (group disbanded, conversation replaced, fold) would otherwise leave the dialog blocking
 * the whole screen. The claim is released when the page's view is destroyed, and released by its
 * token only, so it never closes a dialog another claim holds. Same behaviour in a shell Activity.
 *
 * Hold one per flow (a field per flow: two flows sharing one claim release each other's wait), and
 * call [show] / [dismiss] from that flow only; a flow that can overlap itself must not start twice.
 * Main thread.
 */
class PageWait(private val page: Fragment) {

    private var token = ComposeDialogManager.NO_CLAIM
    private var boundTo: LifecycleOwner? = null

    private val releaseOnDestroy = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) dismiss()
    }

    val held: Boolean get() = token != ComposeDialogManager.NO_CLAIM

    /** Shows the wait dialog unless this page already holds it or has no Activity any more. */
    fun show(message: String = "") {
        if (held) {
            if (ComposeDialogManager.isWaitShowing(token)) return
            // Another screen's wait evicted ours from the shared slot: claim it again.
            dismiss()
        }
        val activity = page.activity ?: return
        // Tied to the view while there is one (the page's UI is gone with it), else to the page.
        val owner: LifecycleOwner = page.viewLifecycleOwnerLiveData.value ?: page
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) return
        token = ComposeDialogManager.showWait(activity, message)
        if (!held) return
        owner.lifecycle.addObserver(releaseOnDestroy)
        boundTo = owner
    }

    /** Releases this page's claim only; another page's or screen's dialog is left alone. */
    fun dismiss() {
        boundTo?.lifecycle?.removeObserver(releaseOnDestroy)
        boundTo = null
        if (!held) return
        ComposeDialogManager.dismissWait(token)
        token = ComposeDialogManager.NO_CLAIM
    }

    /** Show while [running], release otherwise (for state-driven waits). */
    fun update(running: Boolean) {
        if (running) show() else dismiss()
    }
}
