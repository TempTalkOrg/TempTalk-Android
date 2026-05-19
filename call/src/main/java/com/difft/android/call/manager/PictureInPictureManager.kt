package com.difft.android.call.manager

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import com.difft.android.base.log.lumberjack.L

/**
 * 管理画中画（Picture-in-Picture）模式
 * 负责 PIP 的初始化、进入和状态管理
 */
class PictureInPictureManager(
    private val activity: Activity,
    private val lifecycle: Lifecycle,
    private val onPipModeChanged: (Boolean) -> Unit,
    private val onPipClosed: (() -> Unit)?
) {
    private lateinit var pipBuilderParams: PictureInPictureParams.Builder
    private var isInitialized = false
    private var isPipParamsSet = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 初始化 PIP 参数
     * 应在 Activity 的 onCreate 中调用
     */
    fun initialize() {
        if (isInitialized) {
            L.w { "[Call] PictureInPictureManager already initialized" }
            return
        }

        if (!isSystemPipEnabledAndAvailable()) {
            L.i { "[Call] PictureInPictureManager: PIP not available on this device" }
            return
        }

        val aspectRatio = Rational(16, 9)
        pipBuilderParams = PictureInPictureParams.Builder()
        pipBuilderParams.setAspectRatio(aspectRatio)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            pipBuilderParams.setAutoEnterEnabled(false)
        }

        // Post 到主线程队列末尾，避免阻塞 Activity 启动关键路径
        mainHandler.post { tryToSetPictureInPictureParams() }

        isInitialized = true
        L.i { "[Call] PictureInPictureManager initialized" }
    }

    /**
     * 检查系统是否支持 PIP
     */
    fun isSystemPipEnabledAndAvailable(): Boolean {
        return android.os.Build.VERSION.SDK_INT >= 26 &&
                activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    /**
     * 尝试进入 PIP 模式
     * @param tag 调用标签，用于日志记录
     * @return 是否成功进入 PIP 模式
     */
    @RequiresApi(android.os.Build.VERSION_CODES.O)
    fun enterPipMode(tag: String? = null): Boolean {
        L.i { "[Call] PictureInPictureManager enterPipMode tag:$tag" }
        
        if (!isSystemPipEnabledAndAvailable()) {
            L.w { "[Call] PictureInPictureManager: PIP not available" }
            return false
        }

        if (!isInitialized) {
            L.w { "[Call] PictureInPictureManager: Not initialized, cannot enter PIP mode" }
            return false
        }

        return try {
            activity.enterPictureInPictureMode(pipBuilderParams.build())
            L.i { "[Call] PictureInPictureManager: Successfully entered PIP mode" }
            true
        } catch (e: Exception) {
            L.e { "[Call] PictureInPictureManager: Failed to enter PIP mode: ${e.message}" }
            false
        }
    }

    /**
     * 处理 PIP 模式变化
     * 应在 Activity 的 onPictureInPictureModeChanged 中调用
     * 
     * @param isInPictureInPictureMode 是否在 PIP 模式
     * @param newConfig 新的配置
     * @param isScreenLocked 屏幕是否锁定（由调用方提供）
     */
    @RequiresApi(android.os.Build.VERSION_CODES.O)
    fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
        isScreenLocked: Boolean = false
    ) {
        L.i { "[Call] PictureInPictureManager onPictureInPictureModeChanged isInPictureInPictureMode:$isInPictureInPictureMode" }
        
        // 通知 PIP 模式变化
        activity.runOnUiThread {
            onPipModeChanged(isInPictureInPictureMode)
        }

        // 处理 PIP 关闭事件
        if (lifecycle.currentState == Lifecycle.State.CREATED) {
            if (!isInPictureInPictureMode) {
                if (isScreenLocked) {
                    L.i { "[Call] PictureInPictureManager: Screen is locked, ignoring PIP close" }
                } else {
                    L.i { "[Call] PictureInPictureManager: PIP closed, handling exit" }
                    onPipClosed?.invoke()
                }
            }
        } else if (lifecycle.currentState == Lifecycle.State.STARTED) {
            // PIP 最大化时触发
            L.d { "[Call] PictureInPictureManager: PIP maximized" }
        }
    }

    /**
     * 尝试设置 PIP 参数（Binder IPC，可能耗时较长）
     */
    @RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun tryToSetPictureInPictureParams() {
        if (isPipParamsSet) return
        try {
            activity.setPictureInPictureParams(pipBuilderParams.build())
            isPipParamsSet = true
            L.i { "[Call] PictureInPictureManager: PIP params set successfully" }
        } catch (e: Exception) {
            L.e { "[Call] PictureInPictureManager: Failed to set PIP params: ${e.message}" }
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        isInitialized = false
        isPipParamsSet = false
        mainHandler.removeCallbacksAndMessages(null)
        L.i { "[Call] PictureInPictureManager released" }
    }
}

