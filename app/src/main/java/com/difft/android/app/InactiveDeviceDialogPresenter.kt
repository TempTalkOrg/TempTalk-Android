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








@Singleton
class InactiveDeviceDialogPresenter @Inject constructor(
    private val deviceActivator: dagger.Lazy<DeviceActivator>,
) {
    
    
    private var dialog: ComposeDialog? = null

    
    fun observe(currentActivity: () -> FragmentActivity?) {
        appScope.launch {
            InactiveDeviceNotifier.pending.collect { pending ->
                withContext(Dispatchers.Main) {
                    if (pending) {
                        currentActivity()?.let { showIfNeeded(it) }
                    } else {
                        
                        
                        dialog?.dismiss()
                        dialog = null
                    }
                }
            }
        }
    }

    
    @MainThread
    fun onActivityResumed(activity: FragmentActivity) = showIfNeeded(activity)

    @MainThread
    private fun showIfNeeded(activity: FragmentActivity) {
        
        
        if (!InactiveDeviceNotifier.pending.value || InactiveDeviceNotifier.isDialogShowing) return
        if (activity.isFinishing || activity.isDestroyed) return

        
        
        dialog?.dismiss()
        
        InactiveDeviceNotifier.markDialogShown()
        
        
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
            
            
            autoDismiss = false,
            onConfirm = { activate() },
            onDismiss = {
                dialog = null
                InactiveDeviceNotifier.onDialogDismissed()
            },
        )
    }

    
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
