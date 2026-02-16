package com.difft.android.base.utils

import android.app.Activity
import android.app.Dialog
import android.view.Window
import android.view.WindowManager
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SecureModeUtil {

    /**
     * 根据屏幕锁状态刷新截屏权限
     * - 有屏幕锁 → 禁止截屏
     * - 无屏幕锁 → 允许截屏
     *
     * 适用于：普通页面（全局 onActivityResumed 调用）
     */
    fun refreshByScreenLock(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (isDebugOrInsider()) {
            clearSecureFlag(activity.window)
            return
        }

        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: return

        scope.launch(Dispatchers.IO) {
            val hasScreenLock = checkHasScreenLock()

            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext

                if (hasScreenLock) {
                    setSecureFlag(activity.window)
                } else {
                    clearSecureFlag(activity.window)
                }
            }
        }
    }

    /**
     * 根据屏幕锁 + 页面策略刷新截屏权限
     * - 有屏幕锁 → 禁止截屏（忽略 pageEnable）
     * - 无屏幕锁 → 根据 pageEnable 决定
     *
     * 适用于：IndexActivity（会话列表禁止，其他 tab 允许）
     */
    fun refreshWithPagePolicy(activity: Activity, pageEnable: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (isDebugOrInsider()) {
            clearSecureFlag(activity.window)
            return
        }

        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: return

        scope.launch(Dispatchers.IO) {
            val hasScreenLock = checkHasScreenLock()

            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext

                if (hasScreenLock || !pageEnable) {
                    setSecureFlag(activity.window)
                } else {
                    clearSecureFlag(activity.window)
                }
            }
        }
    }

    /**
     * 强制禁止截屏（不检查屏幕锁）
     *
     * 适用于：ChatActivity / GroupChatContentActivity
     */
    fun forceDisable(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (isDebugOrInsider()) {
            clearSecureFlag(activity.window)
            return
        }

        setSecureFlag(activity.window)
    }

    /**
     * 根据屏幕锁状态设置 Dialog 的安全模式
     * - 有屏幕锁 → 禁止截屏
     * - 无屏幕锁 → 允许截屏
     *
     * 适用于：BottomSheetDialog 等独立 Window 的 Dialog
     */
    fun applySecureFlagToDialog(dialog: Dialog) {
        if (isDebugOrInsider()) {
            clearSecureFlag(dialog.window)
            return
        }

        if (checkHasScreenLock()) {
            setSecureFlag(dialog.window)
        }
    }

    // ==================== Private Helper Methods ====================

    private fun isDebugOrInsider(): Boolean {
        return BuildConfig.DEBUG || globalServices.environmentHelper.isInsiderChannel()
    }

    private fun checkHasScreenLock(): Boolean {
        val userData = globalServices.userManager.getUserData()
        return userData?.let { !it.passcode.isNullOrEmpty() || !it.pattern.isNullOrEmpty() } ?: false
    }

    private fun setSecureFlag(window: Window?) {
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    private fun clearSecureFlag(window: Window?) {
        window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}