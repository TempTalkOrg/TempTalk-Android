package com.difft.android.app

import androidx.annotation.MainThread
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.session.InactiveDeviceNotifier
import com.difft.android.base.utils.appScope
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.websocket.DeviceActivator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the background 451 signal ([InactiveDeviceNotifier]) into an app-wide activation dialog.
 *
 * The socket can be rejected with no Activity anywhere, so the prompt cannot live in a fragment.
 * Same shape as the MITM-warning bridge in [TempTalkApplication]: the flow collector shows it when
 * a host is already resumed, and `onActivityResumed` retries a flag raised with none.
 */
@Singleton
class InactiveDeviceDialogPresenter @Inject constructor(
    private val deviceActivator: dagger.Lazy<DeviceActivator>,
) {
    // Single dialog owner. Only ever touched on the main thread (see showIfNeeded's @MainThread
    // contract), so no synchronisation is needed.
    private var dialog: ComposeDialog? = null

    /** Called once from application startup. */
    fun observe(currentActivity: () -> FragmentActivity?) {
        appScope.launch {
            InactiveDeviceNotifier.pending.collect { pending ->
                withContext(Dispatchers.Main) {
                    if (pending) {
                        currentActivity()?.let { showIfNeeded(it) }
                    } else {
                        // The flag also clears when the socket reconnects without activation; the
                        // prompt is not cancelable, so it must go away with the flag.
                        dialog?.dismiss()
                        dialog = null
                    }
                }
            }
        }
    }

    /** Called from `onActivityResumed` — retries a flag raised while no host was on screen. */
    @MainThread
    fun onActivityResumed(activity: FragmentActivity) = showIfNeeded(activity)

    @MainThread
    private fun showIfNeeded(activity: FragmentActivity) {
        // Double-check on the main thread: observe() and onActivityResumed() race for the same
        // flag, and isDialogShowing is the only thing that makes the second caller a no-op.
        if (!InactiveDeviceNotifier.pending.value || InactiveDeviceNotifier.isDialogShowing) return
        if (activity.isFinishing || activity.isDestroyed) return

        // Drop any stale dialog first: its onDismiss re-arms the notifier, which would undo the
        // markDialogShown() below if it ran after it.
        dialog?.dismiss()
        // Set before showing, so a racing second caller already sees isDialogShowing == true.
        InactiveDeviceNotifier.markDialogShown()
        // Host destroyed before the user acts (a config change tears down the ComposeView):
        // re-arm so the next resumed Activity re-shows it.
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                dialog = null
                InactiveDeviceNotifier.onDialogDismissed()
            }
        })
        dialog = ComposeDialogManager.showMessageDialog(
            context = activity,
            title = activity.getString(com.difft.android.chat.R.string.activate_device_title),
            message = activity.getString(com.difft.android.chat.R.string.activate_device_tips),
            confirmText = activity.getString(com.difft.android.chat.R.string.activate_device),
            cancelable = false,
            showCancel = false,
            // The device stays unusable until activation succeeds, so the prompt must survive a
            // failed attempt; it is dismissed explicitly on success instead.
            autoDismiss = false,
            onConfirm = { activate() },
            onDismiss = {
                dialog = null
                InactiveDeviceNotifier.onDialogDismissed()
            },
        )
    }

    /** Activation failure leaves the prompt up: the device stays unusable until it succeeds. */
    private fun activate() {
        appScope.launch {
            try {
                val result = deviceActivator.get().activate()
                if (result.status == 0) {
                    L.i { "[InactiveDevice] activation succeeded" }
                    ToastUtil.show(com.difft.android.chat.R.string.activate_device_success)
                    withContext(Dispatchers.Main) {
                        dialog?.dismiss()
                        dialog = null
                    }
                    InactiveDeviceNotifier.clear()
                } else {
                    L.w { "[InactiveDevice] activation rejected status=${result.status}" }
                    result.reason?.let { ToastUtil.show(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[InactiveDevice] activate failed: ${e.stackTraceToString()}" }
                e.message?.let { ToastUtil.show(it) }
            }
        }
    }
}
