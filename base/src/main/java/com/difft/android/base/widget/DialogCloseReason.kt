package com.difft.android.base.widget


enum class DialogCloseReason {
    
    CONFIRM,

    
    CANCEL,

    
    OUTSIDE,

    
    PROGRAMMATIC,
}






internal class MessageDialogCloser(
    private val unmount: () -> Unit,
    private val onDismiss: () -> Unit,
    private val onClosed: ((DialogCloseReason) -> Unit)?,
) {
    private var closed = false
    private var dismissNotified = false

    val isClosed: Boolean get() = closed

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
