package com.difft.android.base.utils.pane

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.widget.ComposeDialogManager












class PageWait(private val page: Fragment) {

    private var token = ComposeDialogManager.NO_CLAIM
    private var boundTo: LifecycleOwner? = null

    private val releaseOnDestroy = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) dismiss()
    }

    val held: Boolean get() = token != ComposeDialogManager.NO_CLAIM

    
    fun show(message: String = "") {
        if (held) {
            if (ComposeDialogManager.isWaitShowing(token)) return
            
            dismiss()
        }
        val activity = page.activity ?: return
        
        val owner: LifecycleOwner = page.viewLifecycleOwnerLiveData.value ?: page
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) return
        token = ComposeDialogManager.showWait(activity, message)
        if (!held) return
        owner.lifecycle.addObserver(releaseOnDestroy)
        boundTo = owner
    }

    
    fun dismiss() {
        boundTo?.lifecycle?.removeObserver(releaseOnDestroy)
        boundTo = null
        if (!held) return
        ComposeDialogManager.dismissWait(token)
        token = ComposeDialogManager.NO_CLAIM
    }

    
    fun update(running: Boolean) {
        if (running) show() else dismiss()
    }
}
