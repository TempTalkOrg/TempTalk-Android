package com.difft.android.call.manager

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import com.difft.android.base.call.CallActionType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.AppLockCallbackManager
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallViewModel
import com.difft.android.call.receiver.CallActivityBroadcastReceiver
import com.difft.android.call.receiver.ScreenUnlockBroadcastReceiver
import com.difft.android.call.state.OnGoingCallStateManager
import org.difft.android.libraries.denoise_filter.DenoisePluginAudioProcessor

/**
 * 统一管理通话 Activity 的资源清理
 * 负责在 onDestroy 时释放所有资源，防止内存泄漏
 */
class CallCleanupManager(
    private val lifecycle: Lifecycle,
    private val context: Context,
    private val callbackId: String
) {

    /**
     * 执行所有清理操作
     * 按照依赖关系顺序清理资源，确保安全释放
     */
    fun cleanup(
        lifecycleObserver: CallLifecycleObserver?,
        dialogManager: CallDialogManager?,
        handler: android.os.Handler,
        proximitySensorManager: ProximitySensorManager?,
        pictureInPictureManager: PictureInPictureManager?,
        callActivityBroadcastReceiver: CallActivityBroadcastReceiver?,
        screenUnlockBroadcastReceiver: ScreenUnlockBroadcastReceiver?,
        serviceManager: CallServiceManager?,
        onGoingCallStateManager: OnGoingCallStateManager,
        callDataManager: CallDataManager,
        vibrationManager: CallVibrationManager,
        ringtoneManager: CallRingtoneManager,
        contactorCacheManager: ContactorCacheManager,
        callControlMessageManager: OnGoingCallStateManager,
        audioProcessor: DenoisePluginAudioProcessor,
        viewModel: LCallViewModel,
        backPressedCallback: androidx.activity.OnBackPressedCallback?
    ) {
        L.i { "[Call] CallCleanupManager cleanup start" }

        // 1. 移除生命周期观察者
        cleanupLifecycleObserver(lifecycleObserver)

        // 2. 清理 UI 相关资源
        cleanupUIResources(dialogManager, backPressedCallback)

        // 3. 清理 Handler 延迟任务
        cleanupHandler(handler)

        // 4. 释放管理器资源
        releaseManagers(
            proximitySensorManager,
            pictureInPictureManager,
            callActivityBroadcastReceiver,
            screenUnlockBroadcastReceiver
        )

        // 5. 清理系统资源
        cleanupSystemResources(vibrationManager, ringtoneManager, contactorCacheManager, callControlMessageManager)

        // 6. 停止服务
        stopService(serviceManager)

        // 7. 重置状态
        resetState(onGoingCallStateManager, callDataManager, audioProcessor, viewModel)

        // 8. 清理监听器
        cleanupListeners()

        // 9. 发送清理广播
        sendDeclineCallBroadcast()

        L.i { "[Call] CallCleanupManager cleanup end" }
    }

    /**
     * 移除生命周期观察者
     */
    private fun cleanupLifecycleObserver(observer: CallLifecycleObserver?) {
        observer?.let {
            lifecycle.removeObserver(it)
            L.d { "[Call] CallCleanupManager removed lifecycle observer" }
        }
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
        L.d { "[Call] CallCleanupManager dismissed all dialogs" }

        // 移除返回键回调
        backPressedCallback?.let {
            if (it.isEnabled) {
                it.remove()
                L.d { "[Call] CallCleanupManager removed back pressed callback" }
            }
        }
    }

    /**
     * 清理 Handler 的延迟任务
     */
    private fun cleanupHandler(handler: android.os.Handler) {
        handler.removeCallbacksAndMessages(null)
        L.d { "[Call] CallCleanupManager cleared handler callbacks" }
    }

    /**
     * 释放各种管理器资源
     */
    private fun releaseManagers(
        proximitySensorManager: ProximitySensorManager?,
        pictureInPictureManager: PictureInPictureManager?,
        callActivityBroadcastReceiver: CallActivityBroadcastReceiver?,
        screenUnlockBroadcastReceiver: ScreenUnlockBroadcastReceiver?
    ) {
        // 释放传感器管理器
        proximitySensorManager?.release()
        L.d { "[Call] CallCleanupManager released proximity sensor manager" }

        // 释放 PIP 管理器
        pictureInPictureManager?.release()
        L.d { "[Call] CallCleanupManager released PIP manager" }

        // 注销广播接收器
        callActivityBroadcastReceiver?.release(context)
        L.d { "[Call] CallCleanupManager unregistered call activity broadcast receiver" }

        screenUnlockBroadcastReceiver?.release(context)
        L.d { "[Call] CallCleanupManager unregistered screen unlock broadcast receiver" }
    }

    /**
     * 清理系统资源（铃声、震动、缓存等）
     */
    private fun cleanupSystemResources(
        vibrationManager: CallVibrationManager,
        ringtoneManager: CallRingtoneManager,
        contactorCacheManager: ContactorCacheManager,
        callControlMessageManager: OnGoingCallStateManager
    ) {
        vibrationManager.stopVibration()
        ringtoneManager.stopRingTone()
        contactorCacheManager.clearContactorCache()
        callControlMessageManager.clearControlMessage()
        L.d { "[Call] CallCleanupManager cleaned up system resources" }
    }

    /**
     * 停止前台服务
     */
    private fun stopService(serviceManager: CallServiceManager?) {
        serviceManager?.stopOngoingCallService()
        L.d { "[Call] CallCleanupManager stopped foreground service" }
    }

    /**
     * 重置状态管理器和音频处理器
     */
    private fun resetState(
        onGoingCallStateManager: OnGoingCallStateManager,
        callDataManager: CallDataManager,
        audioProcessor: DenoisePluginAudioProcessor,
        viewModel: LCallViewModel
    ) {
        // 重置状态管理器
        onGoingCallStateManager.reset()
        L.d { "[Call] CallCleanupManager reset state manager" }

        // 释放音频处理器
        audioProcessor.release()
        L.d { "[Call] CallCleanupManager released audio processor" }

        // 更新通话状态
        viewModel.getRoomId()?.let { roomId ->
            callDataManager.updateCallingState(roomId, false)
            L.d { "[Call] CallCleanupManager updated calling state for room: $roomId" }
        }
    }

    /**
     * 清理监听器
     */
    private fun cleanupListeners() {
        AppLockCallbackManager.removeListener(callbackId)
        L.d { "[Call] CallCleanupManager removed app lock listener" }
    }

    /**
     * 发送拒绝通话广播
     */
    private fun sendDeclineCallBroadcast() {
        try {
            Intent(LCallActivity.ACTION_IN_CALLING_CONTROL).apply {
                putExtra(
                    LCallActivity.EXTRA_CONTROL_TYPE,
                    CallActionType.DECLINE.type
                )
                setPackage(context.packageName)
                context.sendBroadcast(this)
            }
            L.d { "[Call] CallCleanupManager sent decline call broadcast" }
        } catch (e: IllegalStateException) {
            L.w(e) { "[Call] CallCleanupManager Failed to send decline broadcast" }
        }
    }
}

