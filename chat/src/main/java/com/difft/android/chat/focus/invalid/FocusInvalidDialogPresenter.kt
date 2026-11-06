package com.difft.android.chat.focus.invalid

import androidx.annotation.MainThread
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.R
import com.difft.android.chat.focus.FocusActionLauncher
import com.difft.android.chat.focus.FocusGroupDirectory
import com.difft.android.chat.focus.settings.FocusGroupPickerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide forced dialog for "the focus group is gone" (D5 dismissed / expired, D5b removed). Same shape as
 * `InactiveDeviceDialogPresenter`: a flow collector shows it when a host is resumed, `onActivityResumed`
 * retries when none was, and a host that dies re-arms it. It cannot be closed: "End Focus" ends focus,
 * "Reselect" opens the group picker; cancelling the picker keeps the invalid state (the home strip then
 * shows) and marks the version handled. It disappears when the pending state clears (any device acted).
 */
@Singleton
class FocusInvalidDialogPresenter @Inject constructor(
    private val controller: FocusInvalidController,
    private val launcher: FocusActionLauncher,
    private val groups: FocusGroupDirectory,
    private val nameCache: FocusGroupNameCache,
) {
    // Main thread only (see showIfNeeded's contract), so no synchronisation.
    private var dialog: ComposeDialog? = null
    private var shownEpisode: String? = null
    private var resolving = false
    private var currentHost: (() -> FragmentActivity?)? = null

    /** Called once from application startup; [currentActivity] returns the resumed host, if any. */
    fun observe(currentActivity: () -> FragmentActivity?) {
        currentHost = currentActivity
        appScope.launch {
            controller.pendingDialog.collect { pending ->
                withContext(Dispatchers.Main) {
                    if (pending == null || pending.episode != shownEpisode) {
                        // Answered, another device changed the state, or a new episode: the dialog is not cancelable, so it must go with it.
                        dialog?.dismiss()
                        dialog = null
                    }
                    if (pending != null) currentActivity()?.let { showIfNeeded(it) }
                }
            }
        }
        appScope.launch {
            // A reselect that failed leaves the state invalid: show the dialog again once the switch is over.
            launcher.reselectInFlight.collect { inFlight ->
                if (!inFlight) withContext(Dispatchers.Main) { currentActivity()?.let { showIfNeeded(it) } }
            }
        }
    }

    /** Called from `onActivityResumed`: retries a dialog raised while no host was on screen. */
    @MainThread
    fun onActivityResumed(activity: FragmentActivity) = showIfNeeded(activity)

    @MainThread
    private fun showIfNeeded(activity: FragmentActivity) {
        val pending = controller.pendingDialog.value ?: return
        if (!controller.isSignedIn) return
        if (dialog != null || resolving || launcher.reselectInFlight.value) return
        if (activity.isFinishing || activity.isDestroyed) return
        resolving = true
        activity.lifecycleScope.launch {
            val name = try {
                pending.gid?.let { gid -> groups.name(gid)?.takeIf { it.isNotBlank() } ?: nameCache.nameFor(gid) }
            } finally {
                resolving = false
            }
            val current = controller.pendingDialog.value
            if (current?.episode != pending.episode || dialog != null) return@launch
            // The name lookup may outlive the host's foreground time: a finishing or stopped host gets no
            // dialog, and the observer below would never see the stop that already happened. The host on
            // top may have been skipped while this lookup held `resolving`, so it is retried right away.
            if (activity.isFinishing || activity.isDestroyed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                currentHost?.invoke()?.takeIf { it !== activity }?.let { showIfNeeded(it) }
                return@launch
            }
            present(activity, current, name)
        }
    }

    private fun present(activity: FragmentActivity, pending: PendingFocusDialog, groupName: String?) {
        L.i { "[GroupFocus] invalid dialog show kind=${pending.kind} version=${pending.version}" }
        shownEpisode = pending.episode
        // Host stopped or destroyed before the user answers (another page on top, config change): let the
        // dialog go with it so the next resumed host re-shows it instead of a stopped host holding the handle.
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = release(owner)

            override fun onDestroy(owner: LifecycleOwner) = release(owner)

            private fun release(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                if (shownEpisode != null) dialog?.dismiss()
                dialog = null
                // The page on top may already be resumed (its resume came before this stop): show there now.
                currentHost?.invoke()?.takeIf { it !== activity }?.let { showIfNeeded(it) }
            }
        })
        dialog = ComposeDialogManager.showMessageDialog(
            context = activity,
            title = activity.getString(if (pending.kind == FocusInvalidKind.DISBANDED) R.string.focus_invalid_disbanded_title else R.string.focus_invalid_removed_title),
            message = message(activity, pending.kind, groupName),
            // Left "End Focus", right "Reselect" (primary), as in the prototype.
            cancelText = activity.getString(R.string.focus_dialog_end_focus),
            confirmText = activity.getString(R.string.focus_invalid_reselect),
            showCancel = true,
            cancelable = false,
            autoDismiss = false,
            onCancel = { launcher.endFocus(activity) },
            onConfirm = { reselect(activity, pending.episode) },
            onDismiss = { dialog = null },
        )
    }

    private fun message(activity: FragmentActivity, kind: FocusInvalidKind, groupName: String?): String = when {
        kind == FocusInvalidKind.DISBANDED && groupName != null -> activity.getString(R.string.focus_invalid_disbanded_message, groupName)
        kind == FocusInvalidKind.DISBANDED -> activity.getString(R.string.focus_invalid_disbanded_message_generic)
        groupName != null -> activity.getString(R.string.focus_invalid_removed_message, groupName)
        else -> activity.getString(R.string.focus_invalid_removed_message_generic)
    }

    /**
     * Opens the picker in reselect mode: the picker itself applies the chosen group (the end time is
     * kept) or records the cancel, so a host recreated while it is open loses nothing.
     */
    private fun reselect(activity: FragmentActivity, episode: String) {
        // Guarded from here until the picker answered, then closed: a dialog left behind (or re-shown
        // on this host while the picker is open) would let "End Focus" race the switch.
        launcher.beginReselect()
        dialog?.dismiss()
        dialog = null
        activity.startActivity(FocusGroupPickerActivity.reselectIntent(activity, episode))
    }
}
