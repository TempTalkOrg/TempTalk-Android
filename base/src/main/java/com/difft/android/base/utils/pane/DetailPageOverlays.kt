package com.difft.android.base.utils.pane

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager







fun Fragment.dismissWhenLeavingVisibleTop(dismiss: () -> Unit) {
    if (!isInDetailStack()) return
    detailBackOwner().lifecycle.addObserver(
        LifecycleEventObserver { _, event ->
            
            if (event == Lifecycle.Event.ON_STOP) dismiss()
        }
    )
}













fun <T : ComposeDialog?> Fragment.dismissWithView(dialog: T): T {
    if (dialog == null) return dialog
    val viewOwner = viewLifecycleOwnerLiveData.value
    val owner: LifecycleOwner = viewOwner ?: this
    
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
