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

/**
 * Compose Dialog Manager
 * 用于替换DialogX框架的Compose实现
 *
 * The composables it mounts live next to it: [MessageDialog], [WaitDialog] (state in
 * [WaitDialogSlot]), [BottomDialog] / [FullScreenBottomDialog]; [ComposeDialog] and
 * [getSafeActivity] are in `ComposeDialog.kt`.
 */
object ComposeDialogManager {
    /**
     * 对话框类型
     *
     * Material palette hues, not Difft tokens: the design library defines no tip/snackbar colours yet.
     */
    enum class DialogType(val color: Color) {
        SUCCESS(Color(0xFF4CAF50)),
        ERROR(Color(0xFFF44336)),
        WARNING(Color(0xFFFF9800)),
        INFO(Color(0xFF2196F3))
    }

    /**
     * 显示提示消息 (替换TipDialog)
     * @param fragment Fragment实例
     * @param message 消息内容
     * @param type 对话框类型
     * @param duration 显示时长(毫秒)
     * @param actionText 操作按钮文本
     * @param onAction 操作按钮点击回调
     * @param onDismiss 消失回调
     */
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

    /**
     * 显示提示消息 (替换TipDialog) - Activity版本
     */
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

    /**
     * 显示简单提示 (替换PopTip)
     */
    fun showPopTip(
        fragment: Fragment,
        message: String,
        duration: Int = Snackbar.LENGTH_SHORT
    ) {
        val view = fragment.requireView()
        showTipInternal(view, message, DialogType.INFO, duration, null, null, null)
    }

    /**
     * 显示简单提示 (替换PopTip) - Activity版本
     */
    fun showPopTip(
        activity: FragmentActivity,
        message: String,
        duration: Int = Snackbar.LENGTH_SHORT,
        onDismiss: () -> Unit = {}
    ) {
        val view = activity.findViewById<View>(android.R.id.content)
        showTipInternal(view, message, DialogType.INFO, duration, null, null, onDismiss)
    }

    // === 直接调用API - 适用于传统Activity ===

    /**
     * 在Context中显示消息对话框 - 自动安全转换为Activity，如果转换失败则不显示
     */
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
            // 如果无法获取Activity，返回一个空的ComposeDialog实现
            object : ComposeDialog {
                override fun dismiss() {
                    // 什么都不做
                }
            }
        }
    }

    /**
     * 在Context中显示消息对话框 - 支持自定义View，自动安全转换为Activity
     */
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
                        .padding(0.dp), // 去掉默认内边距
                    update = { view ->
                        onViewCreated(view)
                    }
                )
            }
        )
    }


    /**
     * Java 友好的 showMessageDialog 方法 - 接受Context并安全转换为Activity
     */
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

    // 全局WaitDialog管理 — 状态与逻辑都在 [WaitDialogSlot]，这里只保留调用面。
    /** No claim taken: releasing it closes nothing. See [WaitDialogSlot.NO_CLAIM]. */
    const val NO_CLAIM = WaitDialogSlot.NO_CLAIM

    /**
     * 显示全局WaitDialog - 类似DialogX的使用方式
     * @param context Context实例
     * @param message 消息内容
     * @param cancelable 是否可取消
     * @param layoutId 自定义布局ID，传入时使用透明背景
     * @param onViewCreated 自定义View创建回调
     * @return the claim token to pass to [dismissWait]
     */
    @JvmOverloads
    fun showWait(
        context: Context,
        message: String = "",
        cancelable: Boolean = false,
        layoutId: Int? = null,
        onDismiss: () -> Unit = {},
        onViewCreated: (View) -> Unit = {},
    ): Int = WaitDialogSlot.showWait(context, message, cancelable, layoutId, onDismiss, onViewCreated)

    /** 关闭全局WaitDialog - 类似DialogX的使用方式. See [WaitDialogSlot.dismissWait]. */
    @JvmStatic
    fun dismissWait() = WaitDialogSlot.dismissWait()

    /** Releases the wait dialog only while [token] is the live claim. See [WaitDialogSlot]. */
    @JvmStatic
    fun dismissWait(token: Int) = WaitDialogSlot.dismissWait(token)

    /** Whether the dialog [token] claimed is still the one on screen. */
    @JvmStatic
    fun isWaitShowing(token: Int): Boolean = WaitDialogSlot.isWaitShowing(token)

    /** Releases the wait dialog only when the slot holds the one [owner] showed. */
    @JvmStatic
    fun dismissWait(owner: Activity) = WaitDialogSlot.dismissWait(owner)


    /**
     * 在Activity中显示普通底部Dialog - 直接调用API (Compose内容)
     */
    fun showBottomDialog(
        activity: Activity,
        onDismiss: () -> Unit = {},
        content: @Composable () -> Unit
    ): ComposeDialog {
        val composeView = ComposeView(activity)

        val dialog = object : ComposeDialog {
            override fun dismiss() {
                ComposeActivityMount.unmount(activity, composeView)
                onDismiss()
            }
        }

        composeView.setContent {
            DialogTheme {
                var showSheet by remember { mutableStateOf(true) }

                // 如果弹窗被意外关闭，确保清理资源
                if (!showSheet) {
                    ComposeActivityMount.unmount(activity, composeView)
                    return@DialogTheme
                }

                BottomDialog(
                    isVisible = showSheet,
                    onDismiss = {
                        showSheet = false
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

    /**
     * 在Activity中显示普通底部Dialog - 直接调用API (自定义View)
     * @return 返回Dialog对象
     */
    fun showBottomDialog(
        activity: Activity,
        layoutId: Int,
        onDismiss: () -> Unit = {},
        onViewCreated: (View) -> Unit = {}
    ): ComposeDialog {
        val composeView = ComposeView(activity)

        val dialog = object : ComposeDialog {
            override fun dismiss() {
                ComposeActivityMount.unmount(activity, composeView)
                onDismiss()
            }
        }

        composeView.setContent {
            DialogTheme {
                var showSheet by remember { mutableStateOf(true) }

                // 如果弹窗被意外关闭，确保清理资源
                if (!showSheet) {
                    ComposeActivityMount.unmount(activity, composeView)
                    return@DialogTheme
                }

                BottomDialog(
                    isVisible = showSheet,
                    onDismiss = {
                        showSheet = false
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

    /**
     * 在Activity中显示全屏底部Dialog - 直接调用API (Compose内容)
     */
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

                // 如果弹窗被意外关闭，确保清理资源
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

    /**
     * 在Activity中显示全屏底部Dialog - 直接调用API (自定义View)
     */
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

                // 如果弹窗被意外关闭，确保清理资源
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
