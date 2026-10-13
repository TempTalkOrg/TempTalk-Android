package com.difft.android.call.manager

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Rational
import androidx.lifecycle.Lifecycle
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 管理画中画（Picture-in-Picture）模式
 * 负责 PIP 的初始化、进入和状态管理
 */
class PictureInPictureManager(
    private val activity: Activity,
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope,
    private val onPipModeChanged: (Boolean) -> Unit
) {
    private lateinit var pipBuilderParams: PictureInPictureParams.Builder
    private var isInitialized = false
    private var isPipParamsSet = false
    private var pipParamsJob: Job? = null

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

        pipParamsJob = scope.launch(Dispatchers.Main) { tryToSetPictureInPictureParams() }

        isInitialized = true
        L.i { "[Call] PictureInPictureManager initialized" }
    }

    /**
     * 检查系统是否支持 PIP
     */
    fun isSystemPipEnabledAndAvailable(): Boolean {
        return activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    /**
     * 尝试进入 PIP 模式
     * @param tag 调用标签，用于日志记录
     * @return 是否成功进入 PIP 模式
     */
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
     * The window going away never ends the call. The callback carries no reason, so a window the
     * user dismissed is indistinguishable from one the shell took away — a vendor gesture, another
     * app claiming the PiP slot, a screen the system brought forward. Treating any of those as a
     * hang-up ends calls the user meant to keep, and the ones that read as deliberate are already
     * served by an explicit control: the ongoing-call notification, which carries a hang-up action
     * while the call UI is backgrounded, or the call UI itself once restored.
     *
     * @param isInPictureInPictureMode 是否在 PIP 模式
     * @param newConfig 新的配置
     */
    fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        L.i { "[Call] PictureInPictureManager onPictureInPictureModeChanged isInPictureInPictureMode:$isInPictureInPictureMode" }

        // 通知 PIP 模式变化
        activity.runOnUiThread {
            onPipModeChanged(isInPictureInPictureMode)
        }

        if (!isInPictureInPictureMode) {
            // CREATED = the Activity merely stopped, so the window is gone while the call runs on.
            // STARTED = it was restored to full screen. Logged apart because a field report of
            // "the call vanished" needs to say which of the two happened.
            val stopped = lifecycle.currentState == Lifecycle.State.CREATED
            L.i { "[Call] PictureInPictureManager: PIP window gone, stopped=$stopped, call continues" }
        }
    }

    /**
     * 尝试设置 PIP 参数（Binder IPC，可能耗时较长）
     */
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
        pipParamsJob?.cancel()
        pipParamsJob = null
        L.i { "[Call] PictureInPictureManager released" }
    }
}

