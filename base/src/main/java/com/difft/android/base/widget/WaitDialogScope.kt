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










class WaitDialogHandle(
    private val owner: ComponentActivity,
    lifecycle: Lifecycle = owner.lifecycle
) {

    private var token = WaitDialogSlot.NO_CLAIM

    init {
        
        
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event != Lifecycle.Event.ON_DESTROY) return
                dismiss()
                lifecycle.removeObserver(this)
            }
        }
        lifecycle.addObserver(observer)
    }

    
    val isShowing: Boolean
        get() = token != WaitDialogSlot.NO_CLAIM && ComposeDialogManager.isWaitShowing(token)

    








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






fun Fragment.waitDialogHandle(): WaitDialogHandle =
    WaitDialogHandle(requireActivity(), viewLifecycleOwner.lifecycle)












suspend fun <T> Activity.withWait(message: String = "", block: suspend () -> T): T {
    val token = withContext(Dispatchers.Main.immediate) {
        ComposeDialogManager.showWait(this@withWait, message)
    }
    try {
        return block()
    } finally {
        
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            ComposeDialogManager.dismissWait(token)
        }
    }
}









@Composable
fun WaitDialogEffect(visible: Boolean, message: String = "") {
    val activity = LocalContext.current.getSafeActivity() as? ComponentActivity ?: return
    
    
    val handle = remember(activity) { WaitDialogHandle(activity) }
    DisposableEffect(handle, visible, message) {
        if (visible) handle.show(message)
        onDispose { if (visible) handle.dismiss() }
    }
}
