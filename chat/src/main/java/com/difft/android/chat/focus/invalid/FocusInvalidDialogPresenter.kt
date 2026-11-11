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








@Singleton
class FocusInvalidDialogPresenter @Inject constructor(
    private val controller: FocusInvalidController,
    private val launcher: FocusActionLauncher,
    private val groups: FocusGroupDirectory,
    private val nameCache: FocusGroupNameCache,
) {
    
    private var dialog: ComposeDialog? = null
    private var shownEpisode: String? = null
    private var resolving = false
    private var currentHost: (() -> FragmentActivity?)? = null

    
    fun observe(currentActivity: () -> FragmentActivity?) {
        currentHost = currentActivity
        appScope.launch {
            controller.pendingDialog.collect { pending ->
                withContext(Dispatchers.Main) {
                    if (pending == null || pending.episode != shownEpisode) {
                        
                        dialog?.dismiss()
                        dialog = null
                    }
                    if (pending != null) currentActivity()?.let { showIfNeeded(it) }
                }
            }
        }
        appScope.launch {
            
            launcher.reselectInFlight.collect { inFlight ->
                if (!inFlight) withContext(Dispatchers.Main) { currentActivity()?.let { showIfNeeded(it) } }
            }
        }
    }

    
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
        
        
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = release(owner)

            override fun onDestroy(owner: LifecycleOwner) = release(owner)

            private fun release(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                if (shownEpisode != null) dialog?.dismiss()
                dialog = null
                
                currentHost?.invoke()?.takeIf { it !== activity }?.let { showIfNeeded(it) }
            }
        })
        dialog = ComposeDialogManager.showMessageDialog(
            context = activity,
            title = activity.getString(if (pending.kind == FocusInvalidKind.DISBANDED) R.string.focus_invalid_disbanded_title else R.string.focus_invalid_removed_title),
            message = message(activity, pending.kind, groupName),
            
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

    



    private fun reselect(activity: FragmentActivity, episode: String) {
        
        
        launcher.beginReselect()
        dialog?.dismiss()
        dialog = null
        activity.startActivity(FocusGroupPickerActivity.reselectIntent(activity, episode))
    }
}
