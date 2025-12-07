package com.difft.android.base.widget

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.R
import com.google.android.material.snackbar.Snackbar

/**
 * Dialog接口，提供统一的dismiss方法
 */
interface ComposeDialog {
    fun dismiss()
}

/**
 * Context扩展函数：安全获取Activity
 * 如果无法获取到有效的Activity，返回null
 */
fun Context.getSafeActivity(): Activity? {
    fun isValidActivity(activity: Activity?) = activity != null && !activity.isFinishing && !activity.isDestroyed

    return when (this) {
        is Activity -> {
            if (isValidActivity(this)) this else null
        }

        is ContextWrapper -> {
            var context = this.baseContext
            while (context is ContextWrapper) {
                if (context is Activity) {
                    return if (isValidActivity(context)) context else null
                }
                context = context.baseContext
            }
            null
        }

        else -> null
    }
}

/**
 * Compose Dialog Manager
 * 用于替换DialogX框架的Compose实现
 */
object ComposeDialogManager {
    /**
     * 对话框类型
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
        content: (@Composable () -> Unit)? = null
    ): ComposeDialog? {
        val activity = context.getSafeActivity()
        return if (activity != null) {
            val composeView = androidx.compose.ui.platform.ComposeView(activity)

            val dialog = object : ComposeDialog {
                override fun dismiss() {
                    removeComposeViewFromActivity(activity, composeView)
                    onDismiss()
                }
            }

            composeView.setContent {
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
                    onConfirm = {
                        onConfirm()
                        if (autoDismiss) {
                            removeComposeViewFromActivity(activity, composeView)
                        }
                    },
                    onCancel = {
                        onCancel()
                        if (autoDismiss) {
                            removeComposeViewFromActivity(activity, composeView)
                        }
                    },
                    onDismiss = {
                        if (cancelable) {
                            onDismiss()
                            removeComposeViewFromActivity(activity, composeView)
                        }
                    },
                    content = content
                )
            }
            addComposeViewToActivity(activity, composeView)

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

    // 全局WaitDialog管理
    private var currentWaitDialog: (() -> Unit)? = null

    /**
     * 显示全局WaitDialog - 类似DialogX的使用方式
     * @param context Context实例
     * @param message 消息内容
     * @param cancelable 是否可取消
     * @param layoutId 自定义布局ID，传入时使用透明背景
     * @param onViewCreated 自定义View创建回调
     */
    @JvmOverloads
    fun showWait(
        context: Context,
        message: String = "",
        cancelable: Boolean = false,
        layoutId: Int? = null,
        onDismiss: () -> Unit = {},
        onViewCreated: (View) -> Unit = {},
    ) {
        dismissWait() // 先关闭之前的
        val dialog = showWaitDialog(
            context = context,
            message = message,
            cancelable = cancelable,
            layoutId = layoutId,
            onViewCreated = onViewCreated,
            onDismiss = onDismiss
        )
        currentWaitDialog = dialog?.let { { it.dismiss() } }
    }

    /**
     * 关闭全局WaitDialog - 类似DialogX的使用方式
     */
    @JvmStatic
    fun dismissWait() {
        currentWaitDialog?.invoke()
        currentWaitDialog = null
    }

    /**
     * 在Context中显示等待对话框 - 自动安全转换为Activity，如果转换失败则不显示
     * 内部方法，不对外暴露
     */
    private fun showWaitDialog(
        context: Context,
        message: String = "",
        cancelable: Boolean = true,
        layoutId: Int? = null,
        onViewCreated: (View) -> Unit = {},
        onDismiss: () -> Unit = {}
    ): ComposeDialog? {
        val activity = context.getSafeActivity()
        return if (activity != null) {
            val composeView = androidx.compose.ui.platform.ComposeView(activity)

            val dialog = object : ComposeDialog {
                override fun dismiss() {
                    removeComposeViewFromActivity(activity, composeView)
                }
            }

            composeView.setContent {
                WaitDialog(
                    isVisible = true,
                    message = message,
                    cancelable = cancelable,
                    layoutId = layoutId,
                    onViewCreated = onViewCreated,
                    onDismiss = {
                        onDismiss()
                        dismissWait() // 清理全局状态
                    }
                )
            }
            addComposeViewToActivity(activity, composeView)

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
     * 在Activity中显示普通底部Dialog - 直接调用API (Compose内容)
     */
    fun showBottomDialog(
        activity: Activity,
        onDismiss: () -> Unit = {},
        content: @Composable () -> Unit
    ): ComposeDialog {
        val composeView = androidx.compose.ui.platform.ComposeView(activity)

        val dialog = object : ComposeDialog {
            override fun dismiss() {
                removeComposeViewFromActivity(activity, composeView)
                onDismiss()
            }
        }

        composeView.setContent {
            var showSheet by remember { mutableStateOf(true) }

            // 如果弹窗被意外关闭，确保清理资源
            if (!showSheet) {
                removeComposeViewFromActivity(activity, composeView)
                return@setContent
            }

            BottomDialog(
                isVisible = showSheet,
                onDismiss = {
                    showSheet = false
                    onDismiss()
                    removeComposeViewFromActivity(activity, composeView)
                },
                content = content
            )
        }
        addComposeViewToActivity(activity, composeView)

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
        val composeView = androidx.compose.ui.platform.ComposeView(activity)

        val dialog = object : ComposeDialog {
            override fun dismiss() {
                removeComposeViewFromActivity(activity, composeView)
                onDismiss()
            }
        }

        composeView.setContent {
            var showSheet by remember { mutableStateOf(true) }

            // 如果弹窗被意外关闭，确保清理资源
            if (!showSheet) {
                removeComposeViewFromActivity(activity, composeView)
                return@setContent
            }

            BottomDialog(
                isVisible = showSheet,
                onDismiss = {
                    showSheet = false
                    onDismiss()
                    removeComposeViewFromActivity(activity, composeView)
                },
                layoutId = layoutId,
                onViewCreated = onViewCreated
            )
        }
        addComposeViewToActivity(activity, composeView)

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
        val composeView = androidx.compose.ui.platform.ComposeView(activity)
        composeView.setContent {
            var showSheet by remember { mutableStateOf(true) }

            // 如果弹窗被意外关闭，确保清理资源
            if (!showSheet) {
                removeComposeViewFromActivity(activity, composeView)
                return@setContent
            }

            FullScreenBottomDialog(
                isVisible = showSheet,
                onDismiss = {
                    showSheet = false
                    onDismiss()
                    removeComposeViewFromActivity(activity, composeView)
                },
                backgroundDrawable = backgroundDrawable,
                content = content
            )
        }
        addComposeViewToActivity(activity, composeView)
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
        val composeView = androidx.compose.ui.platform.ComposeView(activity)
        composeView.setContent {
            var showSheet by remember { mutableStateOf(true) }

            // 如果弹窗被意外关闭，确保清理资源
            if (!showSheet) {
                removeComposeViewFromActivity(activity, composeView)
                return@setContent
            }

            FullScreenBottomDialog(
                isVisible = showSheet,
                onDismiss = {
                    showSheet = false
                    onDismiss()
                    removeComposeViewFromActivity(activity, composeView)
                },
                backgroundDrawable = backgroundDrawable,
                layoutId = layoutId,
                onViewCreated = onViewCreated
            )
        }
        addComposeViewToActivity(activity, composeView)
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

    // === 辅助方法 - 用于直接调用API ===
    /**
     * 将ComposeView添加到Activity的根布局中
     */
    private fun addComposeViewToActivity(activity: Activity, composeView: androidx.compose.ui.platform.ComposeView) {
        val rootView = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val frameLayout = android.widget.FrameLayout(activity).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(composeView)
        }
        rootView.addView(frameLayout)

        // 保存引用以便后续移除
        composeView.tag = frameLayout
    }

    /**
     * 从Activity中移除ComposeView
     */
    private fun removeComposeViewFromActivity(activity: Activity, composeView: androidx.compose.ui.platform.ComposeView) {
        val rootView = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val frameLayout = composeView.tag as? android.widget.FrameLayout
        frameLayout?.let {
            rootView.removeView(it)
        }
    }
}

/**
 * 普通底部Dialog - 支持Compose组件
 * 注意：ModalBottomSheet 设计上就是可关闭的
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val bgColor = Color(ContextCompat.getColor(context, R.color.bg_popup))
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    if (isVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = bgColor,
            contentWindowInsets = { BottomSheetDefaults.windowInsets }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        bgColor,
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
            ) {
                content()
            }
        }
    }
}

/**
 * 普通底部Dialog - 支持自定义View (重载)
 * 注意：ModalBottomSheet 设计上就是可关闭的
 */
@Composable
fun BottomDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    layoutId: Int,
    onViewCreated: (View) -> Unit = {}
) {
    BottomDialog(
        isVisible = isVisible,
        onDismiss = onDismiss,
        content = {
            AndroidView(
                factory = { context ->
                    android.view.LayoutInflater.from(context).inflate(layoutId, null)
                },
                modifier = Modifier.fillMaxWidth(),
                update = { view ->
                    onViewCreated(view)
                }
            )
        }
    )
}

/**
 * 全屏底部Dialog - 支持Compose组件
 * 注意：ModalBottomSheet 设计上就是可关闭的
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenBottomDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    backgroundDrawable: android.graphics.drawable.Drawable? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val bgColor = Color(ContextCompat.getColor(context, R.color.bg_popup))
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    if (isVisible) {
        // 如果有自定义背景，我们需要自定义整个 ModalBottomSheet 的外观
        if (backgroundDrawable != null) {
            ModalBottomSheet(
                onDismissRequest = onDismiss,
                sheetState = sheetState,
                dragHandle = null, // 移除默认手势指示器，让背景完全覆盖
                containerColor = Color.Transparent,
                contentWindowInsets = { BottomSheetDefaults.windowInsets }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                        .drawBehind {
                            // ChatBackgroundDrawable 自动计算边框，直接覆盖整个区域
                            backgroundDrawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                            backgroundDrawable.draw(drawContext.canvas.nativeCanvas)
                        }
                ) {
                    // 手动添加拖拽指示器（在背景之上）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(4.dp)
                                .background(
                                    Color.White.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }

                    // 内容区域
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        content()
                    }
                }
            }
        } else {
            // 使用纯色背景的原有逻辑
            ModalBottomSheet(
                onDismissRequest = onDismiss,
                sheetState = sheetState,
                dragHandle = { BottomSheetDefaults.DragHandle() },
                containerColor = bgColor,
                contentWindowInsets = { BottomSheetDefaults.windowInsets }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                        .background(
                            bgColor,
                            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                        )
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 全屏底部Dialog - 支持自定义View (重载)
 * 注意：ModalBottomSheet 设计上就是可关闭的
 */
@Composable
fun FullScreenBottomDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    backgroundDrawable: android.graphics.drawable.Drawable? = null,
    layoutId: Int,
    onViewCreated: (View) -> Unit = {}
) {
    FullScreenBottomDialog(
        isVisible = isVisible,
        onDismiss = onDismiss,
        backgroundDrawable = backgroundDrawable,
        content = {
            AndroidView(
                factory = { context ->
                    android.view.LayoutInflater.from(context).inflate(layoutId, null)
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    onViewCreated(view)
                }
            )
        }
    )
}

/**
 * 等待对话框 (替换WaitDialog)
 */
@Composable
fun WaitDialog(
    isVisible: Boolean,
    message: String = "", // 默认不显示文字
    cancelable: Boolean = true,
    layoutId: Int? = null,
    onViewCreated: (View) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    if (isVisible) {
        val context = LocalContext.current
        val bgColor = Color(ContextCompat.getColor(context, R.color.bg_popup))
        val textColor = Color(ContextCompat.getColor(context, R.color.t_primary))
        val progressColor = Color(ContextCompat.getColor(context, R.color.t_info))

        Dialog(
            onDismissRequest = if (cancelable) onDismiss else {
                { }
            }
        ) {
            // 如果有自定义布局，使用透明背景
            if (layoutId != null) {
                AndroidView(
                    factory = { context ->
                        android.view.LayoutInflater.from(context).inflate(layoutId, null)
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        onViewCreated(view)
                    }
                )
            } else {
                // 默认的等待对话框
                Card(
                    modifier = Modifier
                        .wrapContentWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = bgColor)
                ) {
                    Column(
                        modifier = Modifier
                            .wrapContentWidth()
                            .padding(if (message.isNotEmpty()) 24.dp else 20.dp), // 有文字时用24dp，无文字时用20dp
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = progressColor
                        )
                        if (message.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 消息对话框 (替换MessageDialog)
 * 支持自定义Compose内容、按钮颜色等功能
 */
@Composable
fun MessageDialog(
    isVisible: Boolean,
    title: String,
    message: String = "",
    confirmText: String = "",
    cancelText: String = "",
    showCancel: Boolean = true,
    cancelable: Boolean = true,
    confirmButtonColor: Color? = null,
    cancelButtonColor: Color? = null,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
    onDismiss: () -> Unit = {},
    content: (@Composable () -> Unit)? = null
) {
    if (isVisible) {
        val context = LocalContext.current
        val bgColor = Color(ContextCompat.getColor(context, R.color.bg_popup))
        val titleColor = Color(ContextCompat.getColor(context, R.color.t_primary))
        val contentColor = Color(ContextCompat.getColor(context, R.color.t_secondary))
        val cancelColor = Color(ContextCompat.getColor(context, R.color.t_primary))
        val confirmColor = Color(ContextCompat.getColor(context, R.color.t_info))

        // 使用系统文字资源
        val defaultConfirmText = confirmText.ifEmpty {
            context.getString(android.R.string.ok)
        }

        val defaultCancelText = cancelText.ifEmpty {
            context.getString(android.R.string.cancel)
        }

        val actualConfirmColor = confirmButtonColor ?: confirmColor
        val actualCancelColor = cancelButtonColor ?: cancelColor

        AlertDialog(
            onDismissRequest = if (cancelable) onDismiss else {
                { }
            },
            containerColor = bgColor,
            title = if (title.isNotEmpty()) {
                {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        color = titleColor
                    )
                }
            } else null,
            text = {
                // 如果有自定义内容，显示自定义内容；否则显示消息
                if (content != null) {
                    content()
                } else if (message.isNotEmpty()) {
                    Text(
                        text = message,
                        fontSize = 14.sp,
                        color = contentColor
                    )
                } else {
                    // 空内容
                    Spacer(modifier = Modifier.height(8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onConfirm()
                }) {
                    Text(
                        text = defaultConfirmText,
                        fontSize = 14.sp,
                        color = actualConfirmColor
                    )
                }
            },
            dismissButton = if (showCancel) {
                {
                    TextButton(onClick = {
                        onCancel()
                    }) {
                        Text(
                            text = defaultCancelText,
                            fontSize = 14.sp,
                            color = actualCancelColor
                        )
                    }
                }
            } else null
        )
    }
}

// Preview functions for Compose Dialog components
@Preview(showBackground = true, name = "Full Screen Bottom Dialog Component Preview")
@Composable
fun FullScreenBottomDialogComponentPreview() {
    FullScreenBottomDialog(
        isVisible = true,
        onDismiss = { }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "全屏底部Dialog组件",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = "这是ComposeDialogManager中的全屏底部Dialog组件",
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@Preview(showBackground = true, name = "Bottom Dialog Component Preview")
@Composable
fun BottomDialogComponentPreview() {
    BottomDialog(
        isVisible = true,
        onDismiss = { }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "普通底部Dialog组件",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = "这是ComposeDialogManager中的普通底部Dialog组件",
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@Preview(showBackground = true, name = "Wait Dialog Component Preview")
@Composable
fun WaitDialogComponentPreview() {
    WaitDialog(
        isVisible = true,
        message = "正在处理中..."
    )
}

@Preview(showBackground = true, name = "Message Dialog Component Preview")
@Composable
fun MessageDialogComponentPreview() {
    MessageDialog(
        isVisible = true,
        title = "确认操作",
        message = "您确定要执行此操作吗？",
        confirmText = "确定",
        cancelText = "取消",
        showCancel = true,
        onConfirm = { },
        onCancel = { },
        onDismiss = { }
    )
}
