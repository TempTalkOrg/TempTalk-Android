package com.difft.android.base.widget

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One flow's claim on the global wait dialog: [show] raises it and takes a claim token, [dismiss]
 * releases only that claim.
 *
 * The invariant is token-level, not screen-level: a release only ever closes the window this handle
 * holds a share of, and only once no other claim holds one; one handle per flow. Handles on the same
 * Activity share one window, so it closes when the last claim is released; a different Activity
 * evicts. That is what a screen with several flows needs: a next button, a resend and the sign-in
 * chain each hold their own handle, so releasing one cannot pull the modal out from under another,
 * and a sticky LiveData replay or a settled request cannot close a modal it did not raise.
 *
 * The handle is held for the life of the screen, and [show] while its own dialog is up is a no-op,
 * so a chained request keeps the window it already has instead of blinking it off and on between
 * its legs. Not thread-safe: main thread only, like the dialog it drives.
 *
 * Released automatically when [lifecycle] is destroyed; Fragments must use [waitDialogHandle] so the
 * view lifecycle, not the Activity, bounds the claim.
 *
 * Non-cancelable by design (issue #1208), matching the iOS `DTToastHelper` HUD: the request's own
 * timeout is the user-visible bound, so every path out of a flow must release.
 */
class WaitDialogHandle(
    private val owner: ComponentActivity,
    lifecycle: Lifecycle = owner.lifecycle
) {

    private var token = WaitDialogSlot.NO_CLAIM

    init {
        // One-shot: the claim cannot outlive the lifecycle that bounds it, and the observer cannot
        // outlive the claim.
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event != Lifecycle.Event.ON_DESTROY) return
                dismiss()
                lifecycle.removeObserver(this)
            }
        }
        lifecycle.addObserver(observer)
    }

    /** Whether the window this handle holds a claim on is still the one on screen. */
    val isShowing: Boolean
        get() = token != WaitDialogSlot.NO_CLAIM && ComposeDialogManager.isWaitShowing(token)

    /**
     * Raises the dialog and takes a claim, or does nothing while this handle's own dialog is up.
     * [message] is therefore fixed on the first show; dismiss and show again to change it. When
     * another handle on the same Activity already has the window up, this claim joins it and that
     * window's message stays.
     *
     * An owner with no live Activity raises nothing and leaves the handle claimless, so [isShowing]
     * stays false and [dismiss] has nothing to close.
     */
    fun show(message: String = "") {
        if (isShowing) return
        token = ComposeDialogManager.showWait(owner, message)
    }

    fun dismiss() {
        if (token == WaitDialogSlot.NO_CLAIM) return
        ComposeDialogManager.dismissWait(token)
        token = WaitDialogSlot.NO_CLAIM
    }
}

/**
 * A handle bound to this Fragment's view lifecycle, so the claim is released when the view goes
 * away rather than when the Activity does — the Activity outlives the view, and in dual-pane mode
 * sibling Fragments share it. Call from `onViewCreated`: the handle is per-view, like the binding.
 */
fun Fragment.waitDialogHandle(): WaitDialogHandle =
    WaitDialogHandle(requireActivity(), viewLifecycleOwner.lifecycle)

/**
 * Runs [block] behind the global wait dialog and releases it however the block settles — normal
 * return, exception, or scope cancellation. The release is token-scoped, so it closes the dialog
 * this call raised and nothing else, finishing owner included.
 *
 * The dialog goes up on `Main.immediate`, i.e. in the same main-thread turn as the click that
 * started the request, and it is a real modal window: a second tap cannot reach the button while
 * the request is in flight. That is why buttons do not carry an in-flight flag — `enabled` keeps a
 * single owner (input validity), which is what a screen like the profile page needs, since
 * releasing there means recomputing validity, not forcing `true`.
 *
 * Two boundaries of that invariant: the platform window only becomes modal once the composition it
 * was scheduled from produces a frame (the button's click debounce covers that frame), and the slot
 * is global, so another screen calling `showWait` evicts this dialog while this block still runs.
 *
 * Non-cancelable by design (issue #1208), matching the iOS `DTToastHelper` HUD. The block's own
 * timeout is therefore the user-visible bound — for `ChativeHttpClient` calls, its connect and
 * read timeouts.
 */
suspend fun <T> Activity.withWait(message: String = "", block: suspend () -> T): T {
    val token = withContext(Dispatchers.Main.immediate) {
        ComposeDialogManager.showWait(this@withWait, message)
    }
    try {
        return block()
    } finally {
        // NonCancellable: the dismiss must still run when the scope is already cancelled.
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            ComposeDialogManager.dismissWait(token)
        }
    }
}

/**
 * Binds the global wait dialog to [visible] for as long as this composable is in the composition,
 * and releases it on dispose, so a screen that navigates away while still loading cannot leave a
 * non-cancelable modal behind.
 *
 * Only ever releases the dialog this effect raised: a composition that starts with `visible = false`
 * touches nothing, and a dispose while invisible touches nothing.
 */
@Composable
fun WaitDialogEffect(visible: Boolean, message: String = "") {
    val activity = LocalContext.current.getSafeActivity() as? ComponentActivity ?: return
    // One handle per composition: each handle registers a lifecycle observer on the Activity, so
    // creating one per visibility flip would accumulate observers for the Activity's lifetime.
    val handle = remember(activity) { WaitDialogHandle(activity) }
    DisposableEffect(handle, visible, message) {
        if (visible) handle.show(message)
        onDispose { if (visible) handle.dismiss() }
    }
}
