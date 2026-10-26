package com.difft.android.base.widget

/** Why a message dialog left the screen; reported exactly once per dialog through `onClosed`. */
enum class DialogCloseReason {
    /** The confirm button, with `autoDismiss`. */
    CONFIRM,

    /** The cancel button, with `autoDismiss`. */
    CANCEL,

    /** Outside tap or back, on a cancelable dialog. */
    OUTSIDE,

    /** [ComposeDialog.dismiss] called by the code that showed it. */
    PROGRAMMATIC,
}

/**
 * Close bookkeeping of one message dialog: unmount and `onClosed` run at most once, and
 * `onDismiss` runs at most once, for an outside tap, back or [ComposeDialog.dismiss], never for the
 * buttons.
 */
internal class MessageDialogCloser(
    private val unmount: () -> Unit,
    private val onDismiss: () -> Unit,
    private val onClosed: ((DialogCloseReason) -> Unit)?,
) {
    private var closed = false
    private var dismissNotified = false

    fun confirm(autoDismiss: Boolean, onConfirm: () -> Unit) {
        if (closed) return
        onConfirm()
        if (autoDismiss) close(DialogCloseReason.CONFIRM)
    }

    fun cancel(autoDismiss: Boolean, onCancel: () -> Unit) {
        if (closed) return
        onCancel()
        if (autoDismiss) close(DialogCloseReason.CANCEL)
    }

    fun outside(cancelable: Boolean) {
        if (!cancelable || closed) return
        notifyDismiss()
        close(DialogCloseReason.OUTSIDE)
    }

    fun dismiss() {
        close(DialogCloseReason.PROGRAMMATIC)
        notifyDismiss()
    }

    private fun notifyDismiss() {
        if (dismissNotified) return
        dismissNotified = true
        onDismiss()
    }

    private fun close(reason: DialogCloseReason) {
        if (closed) return
        closed = true
        unmount()
        onClosed?.invoke(reason)
    }
}
