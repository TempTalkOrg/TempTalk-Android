package com.difft.android.base.utils.pane

import android.content.Context
import com.difft.android.base.widget.ComposeDialogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext








class TaskWait private constructor(private var wait: PageWait?, private var token: Int) {

    
    fun release() {
        wait?.let {
            wait = null
            it.dismiss()
            return
        }
        if (token == ComposeDialogManager.NO_CLAIM) return
        ComposeDialogManager.dismissWait(token)
        token = ComposeDialogManager.NO_CLAIM
    }

    companion object {
        fun claim(context: Context, wait: PageWait?, message: String = ""): TaskWait {
            if (wait != null) {
                wait.show(message)
                return TaskWait(wait, ComposeDialogManager.NO_CLAIM)
            }
            return TaskWait(null, ComposeDialogManager.showWait(context, message))
        }
    }
}


suspend fun <T> withTaskWait(context: Context, wait: PageWait?, block: suspend () -> T): T {
    val claim = withContext(Dispatchers.Main.immediate) { TaskWait.claim(context, wait) }
    try {
        return block()
    } finally {
        withContext(NonCancellable + Dispatchers.Main.immediate) { claim.release() }
    }
}
