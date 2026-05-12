package com.difft.android.call.ui.barrage

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallViewModel

/**
 * 气泡飘动层的独立窗口宿主。
 *
 * ### 为什么要独立窗口
 *
 * 前面几版把气泡 Composable 放在主 CallSurface 的不同层级 / 做 frozen-size /
 * 用绝对像素 offset，都无法完全消除"菜单/控制栏出现时气泡瞬间跳 10~30dp"
 * 的现象。根本原因是：只要气泡和菜单/控制栏共享同一 Android Window
 * （同一个 `ViewRootImpl` / 同一条 measure→layout→draw 流水线），主 Compose
 * 树里发生的任何一次父 re-measure / WindowInsets 派发 / graphicsLayer 重建
 * 都可能在气泡所在的那一帧产生不可预测的视觉位移。
 *
 * 彻底修法：通过 [WindowManager.addView] 额外挂一个 `TYPE_APPLICATION_PANEL`
 * 窗口，专门渲染气泡。
 *   - 独立的 [ComposeView] / Composition / Recomposer / SnapshotObserver；
 *   - 独立的 measure/layout/draw 流水线；
 *   - 不接收也不拦截任何 touch 事件（`FLAG_NOT_TOUCHABLE` +
 *     `FLAG_NOT_FOCUSABLE`），所有点击全部穿透到底下的主 Activity 窗口。
 *
 * 这样一来气泡窗的尺寸/位置只跟 Android 窗口管理服务本身走，不再被主 UI
 * 的 recomposition / remeasure 所触发。
 *
 * ### 使用方式
 *
 * 在 CallContent 的顶层 Composable 调用一次：
 *
 * ```kotlin
 * BubbleOverlayWindowHost(viewModel = viewModel)
 * ```
 *
 * 该 Composable 本身不会向主树里贡献任何可视内容，它仅持有一个
 * [DisposableEffect] 管理独立窗口的生命周期：进入 composition 时
 * [WindowManager.addView] 挂出去，离开 composition 时 [WindowManager.removeView]
 * 收回来。
 */
@Composable
fun BubbleOverlayWindowHost(viewModel: LCallViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context.findActivity() ?: return

    DisposableEffect(activity, lifecycleOwner) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 独立 ComposeView：承载气泡 Composable。
        // 它不在主 Activity 的视图树里，所以必须手动把 ViewTree 的
        // LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner
        // 挂上去，否则 Compose 会抛 "No LifecycleOwner provided"。
        val composeView = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            (activity as? ViewModelStoreOwner)?.let { setViewTreeViewModelStoreOwner(it) }
            (activity as? SavedStateRegistryOwner)?.let { setViewTreeSavedStateRegistryOwner(it) }
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnLifecycleDestroyed(lifecycleOwner)
            )
            setContent {
                BubbleOverlayLayer(viewModel = viewModel)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // TYPE_APPLICATION_PANEL：挂在当前 Activity 主窗口之上的子窗口
            // （和 Dialog/Popup 同级），生命周期跟随主窗口自动销毁。
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            // 点击不拦截、焦点不抢、硬件加速。
            // 不加 FLAG_LAYOUT_NO_LIMITS：气泡要跟主 Activity 内容区对齐，
            // 不能延伸到状态栏下面，否则 `configuration.screenHeightDp`
            // 算出的基线和 overlay 坐标系会差一个 statusBarHeight。
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 绑定到主 Activity 的 window token：这样系统在主 Activity
            // 销毁 / 切到后台 / 横竖屏切换时，会自动清理这个子窗口，
            // 不会留下幽灵 View。
            token = activity.window.decorView.windowToken
            gravity = Gravity.TOP or Gravity.START
            // 覆盖整个屏幕，不参与 IME 避让 —— 气泡窗不需要被 IME 顶走。
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        try {
            windowManager.addView(composeView, params)
        } catch (t: Throwable) {
            // BadTokenException 等：多见于 Activity 还没 attach 完 window 或
            // 已经开始销毁，此时直接放弃挂载即可，主界面不受影响。
            L.w(t) { "[BubbleOverlay] addView failed, skip overlay window" }
        }

        onDispose {
            runCatching { windowManager.removeView(composeView) }
                .onFailure { L.w(it) { "[BubbleOverlay] removeView failed" } }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
