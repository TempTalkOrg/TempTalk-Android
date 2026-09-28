package com.difft.android.base.widget

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.difft.android.base.log.lumberjack.L

/**
 * The one global wait-dialog slot: at most one wait dialog is on screen, and a claim token says
 * which flow put it there.
 *
 * Ownership is token-level rather than screen-level because one screen runs several flows through
 * this slot — a next button, a resend, a chained sign-in — and sibling Fragments share an Activity.
 * Claims on the same Activity therefore share one window, reference counted: it closes when the
 * last claim is released. A different Activity evicts. The invariant is: a release only ever closes
 * a window this claim holds a share of, never one another screen raised; one handle per flow. That
 * handle is [WaitDialogHandle], which is what a call site should hold.
 *
 * [ComposeDialogManager] delegates its wait-dialog entry points here, so every call site keeps the
 * signatures it already uses. Main thread only.
 */
internal object WaitDialogSlot {

    /**
     * The live claim: the Activity the dialog is mounted on, so a release can tell whose dialog the
     * slot holds, the closure that takes that dialog down, and every token still holding a share of
     * it — the window closes once [tokens] empties.
     */
    private class Claim(
        val owner: ComponentActivity,
        val dismiss: () -> Unit,
        val tokens: MutableSet<Int>,
    )

    private var current: Claim? = null

    /**
     * Monotonic token counter: every [showWait] hands out a fresh token, and [dismissWait] with one
     * releases only that token's share of the live window, so one flow can never close a dialog
     * another flow raised.
     */
    private var generation = 0

    /** Drops the destroy watch on the current owner, held so the watch outlives its registration. */
    private var unwatchOwner: (() -> Unit)? = null

    /**
     * Returned when no dialog was raised, so the caller holds no claim. Tokens are handed out from
     * 1, so this can never name a live dialog: releasing it closes nothing and [isWaitShowing] is
     * false for it.
     */
    const val NO_CLAIM = 0

    /**
     * Raises the wait dialog for [context] and returns the claim token to pass to [dismissWait], or
     * [NO_CLAIM] when [context] has no live Activity to mount on — nothing is shown and the slot is
     * left untouched, so no caller can hold a claim on a dialog that does not exist.
     *
     * Ownership keys on the Activity the dialog is mounted on rather than the Context handed in: a
     * Hilt Fragment passes a `FragmentContextWrapper`, and comparing raw Contexts would match no
     * owner, silencing the eviction log and the destroy watch. That Activity has to be a
     * [ComponentActivity] for the destroy watch to exist at all, so anything else claims nothing.
     *
     * A show for the Activity that already owns the slot joins that window instead of rebuilding
     * it, so two flows on one screen cannot evict each other's modal: the claim is reference
     * counted and the window closes once the last token is released. The first claim's [message]
     * and flags stay — re-mounting for a later caller's text would blink the live modal off and on,
     * and the slot takes no view on who asked. A different Activity still evicts.
     */
    fun showWait(
        context: Context,
        message: String = "",
        cancelable: Boolean = false,
        layoutId: Int? = null,
        onDismiss: () -> Unit = {},
        onViewCreated: (View) -> Unit = {},
    ): Int {
        val activity = context.getSafeActivity() as? ComponentActivity ?: return NO_CLAIM
        val previous = current
        // Same screen: share the live window rather than remount it, and refcount the claim.
        if (previous != null && previous.owner === activity) {
            val shared = ++generation
            previous.tokens.add(shared)
            return shared
        }
        // The slot is global: a second screen showing a wait dialog silently closes the first
        // screen's, whose request is still in flight with nothing on screen to say so. Log it so
        // the failure is diagnosable; class names only, no user data. A destroyed owner never gets
        // here: its destroy watch already cleared the slot.
        if (previous != null) {
            L.w {
                "[WaitDialogSlot] showWait evicted wait dialog owner=${previous.owner.javaClass.simpleName} " +
                        "by=${activity.javaClass.simpleName}"
            }
        }
        dismissWait() // 先关闭之前的
        val dialog = showWaitDialog(
            activity = activity,
            message = message,
            cancelable = cancelable,
            layoutId = layoutId,
            onViewCreated = onViewCreated,
            onDismiss = onDismiss
        )
        val token = ++generation
        current = Claim(activity, { dialog.dismiss() }, mutableSetOf(token))
        watchOwnerDestroy(activity)
        return token
    }

    /**
     * Closes whatever the slot holds, every claim on it included, so it fits a caller that shows and
     * dismisses in one place and nothing else can be in flight. Prefer [dismissWait] with a token.
     */
    fun dismissWait() {
        current?.dismiss?.invoke()
        current = null
        unwatchOwnerDestroy()
    }

    /**
     * Drops [token]'s share of the live window and closes the window once no claim is left, i.e.
     * only a flow that put the dialog on screen can close it, and only the last one out actually
     * does. A token the live claim does not hold releases nothing, which is what makes a release
     * safe on any path that may not have raised the dialog: a settled request whose dialog another
     * screen has since taken over, a sticky LiveData replay, a destroyed view.
     */
    fun dismissWait(token: Int) {
        val claim = current ?: return
        if (!claim.tokens.remove(token)) return
        if (claim.tokens.isEmpty()) dismissWait()
    }

    /** Whether the window [token] holds a share of is still the one on screen. */
    fun isWaitShowing(token: Int): Boolean = current?.tokens?.contains(token) == true

    /**
     * Releases the wait dialog only when the slot holds the one [owner] showed, matched on the
     * Activity the dialog is mounted on — so a Fragment passing its `requireActivity()` matches the
     * dialog it raised with its own Context. Activity-level granularity: it closes whatever that
     * screen currently owns and drops every claim on it, including those other flows on the same
     * screen hold, so a screen running more than one such flow wants [dismissWait] with a token
     * instead.
     */
    fun dismissWait(owner: Activity) {
        if (current?.owner === owner) dismissWait()
    }

    /**
     * Clears the slot when the owner is destroyed, so a screen that failed to release its dialog
     * cannot leave a closure over a dead Activity in a process-lifetime object. Watches only the
     * Activity the live dialog is mounted on: every change of the slot drops the previous watch
     * first, so a screen destroyed after another one took the slot over closes nothing.
     */
    private fun watchOwnerDestroy(owner: ComponentActivity) {
        unwatchOwnerDestroy()
        val lifecycle = owner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dismissWait()
        }
        lifecycle.addObserver(observer)
        unwatchOwner = { lifecycle.removeObserver(observer) }
    }

    private fun unwatchOwnerDestroy() {
        unwatchOwner?.invoke()
        unwatchOwner = null
    }

    /** Mounts the wait dialog on [activity], which [showWait] has already resolved and validated. */
    private fun showWaitDialog(
        activity: Activity,
        message: String,
        cancelable: Boolean,
        layoutId: Int?,
        onViewCreated: (View) -> Unit,
        onDismiss: () -> Unit
    ): ComposeDialog {
        val composeView = ComposeView(activity)

        val dialog = object : ComposeDialog {
            override fun dismiss() {
                ComposeActivityMount.unmount(activity, composeView)
            }
        }

        composeView.setContent {
            DialogTheme {
                WaitDialog(
                    isVisible = true,
                    message = message,
                    cancelable = cancelable,
                    layoutId = layoutId,
                    onViewCreated = onViewCreated,
                    onDismiss = {
                        onDismiss()
                        dismissWait() // 清理全局状态
                    }
                )
            }
        }
        ComposeActivityMount.mount(activity, composeView)

        return dialog
    }
}
