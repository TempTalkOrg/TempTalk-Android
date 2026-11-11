package com.difft.android.base.widget

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.snackbar.Snackbar


object ComposeDialogManager {
    
    enum class DialogType(val color: Color) {
        SUCCESS(Color(0xFF4CAF50)),
        ERROR(Color(0xFFF44336)),
        WARNING(Color(0xFFFF9800)),
        INFO(Color(0xFF2196F3))
    }

    









    fun showTip(
        fragment: Fragment,
        message: String,
        type: DialogType = DialogType.INFO,
        duration: Int = Snackbar.LENGTH_LONG,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val view = fragment.requireView()
        showTipInternal(view, message, type, duration, actionText, onAction, onDismiss)
    }

    fun showTip(
        activity: FragmentActivity,
        message: String,
        type: DialogType = DialogType.INFO,
        duration: Int = Snackbar.LENGTH_LONG,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val view = activity.findViewById<View>(android.R.id.content)
        showTipInternal(view, message, type, duration, actionText, onAction, onDismiss)
    }

    fun showPopTip(
        fragment: Fragment,
        message: String,
        duration: Int = Snackbar.LENGTH_SHORT
    ) {
        val view = fragment.requireView()
        showTipInternal(view, message, DialogType.INFO, duration, null, null, null)
    }

    fun showPopTip(
        activity: FragmentActivity,
        message: String,
        duration: Int = Snackbar.LENGTH_SHORT,
        onDismiss: () -> Unit = {}
    ) {
        val view = activity.findViewById<View>(android.R.id.content)
        showTipInternal(view, message, DialogType.INFO, duration, null, null, onDismiss)
    }


    
    fun showMessageDialog(
        context: Context,
        title: String,
        message: String = "",
        confirmText: String = "",
        cancelText: String = "",
        showCancel: Boolean = true,
        cancelable: Boolean = true,
        autoDismiss: Boolean = true,
        confirmButtonColor: Color? = null,
        cancelButtonColor: Color? = null,
        onConfirm: () -> Unit = {},
        onCancel: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onClosed: ((DialogCloseReason) -> Unit)? = null,
        content: (@Composable () -> Unit)? = null
    ): ComposeDialog? {
        val activity = context.getSafeActivity()
        return if (activity != null) {
            val composeView = ComposeView(activity)
            val closer = MessageDialogCloser(
                unmount = { ComposeActivityMount.unmount(activity, composeView) },
                onDismiss = onDismiss,
                onClosed = onClosed,
            )

            val dialog = object : ComposeDialog {
                override fun dismiss() = closer.dismiss()
                override val isShowing: Boolean get() = !closer.isClosed
            }

            composeView.setContent {
                DialogTheme {
                    MessageDialog(
                        isVisible = true,
                        title = title,
                        message = message,
                        confirmText = confirmText,
                        cancelText = cancelText,
                        showCancel = showCancel,
                        cancelable = cancelable,
                        confirmButtonColor = confirmButtonColor,
                        cancelButtonColor = cancelButtonColor,
                        onConfirm = { closer.confirm(autoDismiss, onConfirm) },
                        onCancel = { closer.cancel(autoDismiss, onCancel) },
                        onDismiss = { closer.outside(cancelable) },
                        content = content
                    )
                }
            }
            ComposeActivityMount.mount(activity, composeView)

            dialog
        } else {
            object : ComposeDialog {
                override fun dismiss() {
                    
                }

                override val isShowing: Boolean get() = false
            }
        }
    }

    
    fun showMessageDialog(
        context: Context,
        title: String,
        message: String = "",
        confirmText: String = "",
        cancelText: String = "",
        showCancel: Boolean = true,
        cancelable: Boolean = true,
        autoDismiss: Boolean = true,
        confirmButtonColor: Color? = null,
        cancelButtonColor: Color? = null,
        onConfirm: () -> Unit = {},
        onCancel: () -> Unit = {},
        onDismiss: () -> Unit = {},
        layoutId: Int,
        onViewCreated: (View) -> Unit = {}
    ): ComposeDialog? {
        return showMessageDialog(
            context = context,
            title = title,
            message = message,
            confirmText = confirmText,
            cancelText = cancelText,
            showCancel = showCancel,
            cancelable = cancelable,
            autoDismiss = autoDismiss,
            confirmButtonColor = confirmButtonColor,
            cancelButtonColor = cancelButtonColor,
            onConfirm = onConfirm,
            onCancel = onCancel,
            onDismiss = onDismiss,
            content = {
                AndroidView(
                    factory = { context ->
                        android.view.LayoutInflater.from(context).inflate(layoutId, null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(0.dp),
                    update = { view ->
                        onViewCreated(view)
                    }
                )
            }
        )
    }


    
    @JvmStatic
    @JvmOverloads
    fun showMessageDialogForJava(
        context: Context,
        title: String,
        message: String,
        confirmText: String,
        cancelText: String,
        showCancel: Boolean = true,
        cancelable: Boolean = true,
        onConfirm: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ): ComposeDialog? {
        return showMessageDialog(
            context = context,
            title = title,
            message = message,
            confirmText = confirmText,
            cancelText = cancelText,
            showCancel = showCancel,
            cancelable = cancelable,
            onConfirm = onConfirm ?: {},
            onCancel = onCancel ?: {},
            onDismiss = onDismiss ?: {}
        )
    }

    
    
    const val NO_CLAIM = WaitDialogSlot.NO_CLAIM

    








    @JvmOverloads
    fun showWait(
        context: Context,
        message: String = "",
        cancelable: Boolean = false,
        layoutId: Int? = null,
        onDismiss: () -> Unit = {},
        onViewCreated: (View) -> Unit = {},
    ): Int = WaitDialogSlot.showWait(context, message, cancelable, layoutId, onDismiss, onViewCreated)

    
    @JvmStatic
    fun dismissWait() = WaitDialogSlot.dismissWait()

    
    @JvmStatic
    fun dismissWait(token: Int) = WaitDialogSlot.dismissWait(token)

    
    @JvmStatic
    fun isWaitShowing(token: Int): Boolean = WaitDialogSlot.isWaitShowing(token)

    
    @JvmStatic
    fun dismissWait(owner: Activity) = WaitDialogSlot.dismissWait(owner)


    fun showBottomDialog(
        activity: Activity,
        onDismiss: () -> Unit = {},
        showDragHandle: Boolean = true,
        content: @Composable () -> Unit
    ): ComposeDialog {
        val composeView = ComposeView(activity)
        var showing = true

        val dialog = object : ComposeDialog {
            override fun dismiss() {
                showing = false
                ComposeActivityMount.unmount(activity, composeView)
                onDismiss()
            }

            override val isShowing: Boolean get() = showing
        }

        composeView.setContent {
            DialogTheme {
                var showSheet by remember { mutableStateOf(true) }

                
                if (!showSheet) {
                    ComposeActivityMount.unmount(activity, composeView)
                    return@DialogTheme
                }

                BottomDialog(
                    isVisible = showSheet,
                    showDragHandle = showDragHandle,
                    onDismiss = {
                        showSheet = false
                        showing = false
                        onDismiss()
                        ComposeActivityMount.unmount(activity, composeView)
                    },
                    content = content
                )
            }
        }
        ComposeActivityMount.mount(activity, composeView)

        return dialog
    }

    



    fun showBottomDialog(
        activity: Activity,
        layoutId: Int,
        onDismiss: () -> Unit = {},
        onViewCreated: (View) -> Unit = {}
    ): ComposeDialog {
        val composeView = ComposeView(activity)
        var showing = true

        val dialog = object : ComposeDialog {
            override fun dismiss() {
                showing = false
                ComposeActivityMount.unmount(activity, composeView)
                onDismiss()
            }

            override val isShowing: Boolean get() = showing
        }

        composeView.setContent {
            DialogTheme {
                var showSheet by remember { mutableStateOf(true) }

                
                if (!showSheet) {
                    ComposeActivityMount.unmount(activity, composeView)
                    return@DialogTheme
                }

                BottomDialog(
                    isVisible = showSheet,
                    onDismiss = {
                        showSheet = false
                        showing = false
                        onDismiss()
                        ComposeActivityMount.unmount(activity, composeView)
                    },
                    layoutId = layoutId,
                    onViewCreated = onViewCreated
                )
            }
        }
        ComposeActivityMount.mount(activity, composeView)

        return dialog
    }

    fun showFullScreenBottomDialog(
        activity: Activity,
        backgroundDrawable: android.graphics.drawable.Drawable? = null,
        onDismiss: () -> Unit = {},
        content: @Composable () -> Unit
    ) {
        val composeView = ComposeView(activity)
        composeView.setContent {
            DialogTheme {
                var showSheet by remember { mutableStateOf(true) }

                
                if (!showSheet) {
                    ComposeActivityMount.unmount(activity, composeView)
                    return@DialogTheme
                }

                FullScreenBottomDialog(
                    isVisible = showSheet,
                    onDismiss = {
                        showSheet = false
                        onDismiss()
                        ComposeActivityMount.unmount(activity, composeView)
                    },
                    backgroundDrawable = backgroundDrawable,
                    content = content
                )
            }
        }
        ComposeActivityMount.mount(activity, composeView)
    }

    fun showFullScreenBottomDialog(
        activity: Activity,
        layoutId: Int,
        backgroundDrawable: android.graphics.drawable.Drawable? = null,
        onDismiss: () -> Unit = {},
        onViewCreated: (View) -> Unit = {}
    ) {
        val composeView = ComposeView(activity)
        composeView.setContent {
            DialogTheme {
                var showSheet by remember { mutableStateOf(true) }

                
                if (!showSheet) {
                    ComposeActivityMount.unmount(activity, composeView)
                    return@DialogTheme
                }

                FullScreenBottomDialog(
                    isVisible = showSheet,
                    onDismiss = {
                        showSheet = false
                        onDismiss()
                        ComposeActivityMount.unmount(activity, composeView)
                    },
                    backgroundDrawable = backgroundDrawable,
                    layoutId = layoutId,
                    onViewCreated = onViewCreated
                )
            }
        }
        ComposeActivityMount.mount(activity, composeView)
    }


    private fun showTipInternal(
        view: View,
        message: String,
        type: DialogType,
        duration: Int,
        actionText: String?,
        onAction: (() -> Unit)?,
        onDismiss: (() -> Unit)?
    ) {
        val snackbar = Snackbar.make(view, message, duration)
            .setBackgroundTint(type.color.toArgb())
            .setTextColor(Color.White.toArgb())

        actionText?.let { text ->
            snackbar.setAction(text) { onAction?.invoke() }
        }

        onDismiss?.let {
            snackbar.addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    it()
                }
            })
        }

        snackbar.show()
    }
}
