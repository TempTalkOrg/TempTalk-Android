package com.difft.android.call.manager

import android.content.Context
import androidx.lifecycle.Lifecycle
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.receiver.ScreenUnlockBroadcastReceiver

/**
 * 统一管理通话 Activity 的资源清理
 * 负责在 onDestroy 时释放本页面实例的资源，防止内存泄漏；通话本身（前台服务、铃声/震动、参会者监听、
 * 近距离熄屏锁、OnGoingCallStateManager 的通话状态）归通话会话所有，由会话结束时自行拆除。
 */
class CallCleanupManager(
    private val lifecycle: Lifecycle,
    private val context: Context,
) {

    /**
     * Releases this page instance's resources, on every destroy: the instance is going away either
     * way, and its observer, dialogs and receivers would otherwise leak and then double up as soon
     * as a new instance registers its own. Each release is isolated.
     */
    fun cleanup(
        lifecycleObserver: CallLifecycleObserver?,
        dialogManager: CallDialogManager?,
        pictureInPictureManager: PictureInPictureManager?,
        screenUnlockBroadcastReceiver: ScreenUnlockBroadcastReceiver?,
        backPressedCallback: androidx.activity.OnBackPressedCallback?,
    ) {
        L.i { "[Call] CallCleanupManager cleanup start" }

        runCatching { cleanupLifecycleObserver(lifecycleObserver) }
            .onFailure { L.e(it) { "[Call] CallCleanupManager: cleanupLifecycleObserver failed" } }

        runCatching { cleanupUIResources(dialogManager, backPressedCallback) }
            .onFailure { L.e(it) { "[Call] CallCleanupManager: cleanupUIResources failed" } }

        runCatching { releaseManagers(pictureInPictureManager, screenUnlockBroadcastReceiver) }
            .onFailure { L.e(it) { "[Call] CallCleanupManager: releaseManagers failed" } }

        L.i { "[Call] CallCleanupManager cleanup end" }
    }

    /**
     * 移除生命周期观察者
     */
    private fun cleanupLifecycleObserver(observer: CallLifecycleObserver?) {
        observer?.let { lifecycle.removeObserver(it) }
    }

    /**
     * 清理 UI 相关资源
     */
    private fun cleanupUIResources(
        dialogManager: CallDialogManager?,
        backPressedCallback: androidx.activity.OnBackPressedCallback?
    ) {
        // 清理对话框
        dialogManager?.dismissAll()

        // 移除返回键回调
        backPressedCallback?.let {
            if (it.isEnabled) {
                it.remove()
            }
        }
    }

    /**
     * 释放页面级管理器资源
     */
    private fun releaseManagers(
        pictureInPictureManager: PictureInPictureManager?,
        screenUnlockBroadcastReceiver: ScreenUnlockBroadcastReceiver?
    ) {
        pictureInPictureManager?.release()
        screenUnlockBroadcastReceiver?.release(context)
    }
}
