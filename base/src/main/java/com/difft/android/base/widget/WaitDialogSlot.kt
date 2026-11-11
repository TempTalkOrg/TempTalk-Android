package com.difft.android.base.widget

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.difft.android.base.log.lumberjack.L







internal object WaitDialogSlot {

    




    private class Claim(
        val owner: ComponentActivity,
        val dismiss: () -> Unit,
        val tokens: MutableSet<Int>,
    )

    private var current: Claim? = null

    




    private var generation = 0

    
    private var unwatchOwner: (() -> Unit)? = null

    




    const val NO_CLAIM = 0

    








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
        
        if (previous != null && previous.owner === activity) {
            val shared = ++generation
            previous.tokens.add(shared)
            return shared
        }
        
        
        
        
        if (previous != null) {
            L.w {
                "[WaitDialogSlot] showWait evicted wait dialog owner=${previous.owner.javaClass.simpleName} " +
                        "by=${activity.javaClass.simpleName}"
            }
        }
        dismissWait()
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

    



    fun dismissWait() {
        current?.dismiss?.invoke()
        current = null
        unwatchOwnerDestroy()
    }

    






    fun dismissWait(token: Int) {
        val claim = current ?: return
        if (!claim.tokens.remove(token)) return
        if (claim.tokens.isEmpty()) dismissWait()
    }

    
    fun isWaitShowing(token: Int): Boolean = current?.tokens?.contains(token) == true

    







    fun dismissWait(owner: Activity) {
        if (current?.owner === owner) dismissWait()
    }

    





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
                        dismissWait()
                    }
                )
            }
        }
        ComposeActivityMount.mount(activity, composeView)

        return dialog
    }
}
