package com.difft.android.base.utils.pane

import android.content.Context
import com.difft.android.base.widget.ComposeDialogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One task's claim on the shared wait dialog, for utilities that may run from a hosted page or
 * from a plain Activity: the page's [PageWait] when the caller passes one (so the wait also goes
 * with the page's view), else a token claim on the Activity. [release] lets go of this claim
 * only, never another flow's: after the first release it no longer touches the page's wait, so a
 * later claim of that wait (the same flow started again) stays up. Main thread.
 */
class TaskWait private constructor(private var wait: PageWait?, private var token: Int) {

    /** Releases this claim; later calls do nothing. */
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

/** Runs [block] behind a [TaskWait] that is released on every path, cancellation included. */
suspend fun <T> withTaskWait(context: Context, wait: PageWait?, block: suspend () -> T): T {
    val claim = withContext(Dispatchers.Main.immediate) { TaskWait.claim(context, wait) }
    try {
        return block()
    } finally {
        withContext(NonCancellable + Dispatchers.Main.immediate) { claim.release() }
    }
}
