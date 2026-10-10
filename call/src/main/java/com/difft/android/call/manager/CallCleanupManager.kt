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

/**
 * 统一管理通话 Activity 的资源清理
 * 负责在 onDestroy 时释放 Activity 级资源，防止内存泄漏；
 * 通话会话级的拆除（停前台服务、重置共享状态、发拒接广播）只在通话真正结束时执行，
 * 配置变更导致的重建会跳过，详见 [cleanup]
 */
class CallCleanupManager(
    private val lifecycle: Lifecycle,
    private val context: Context,
    private val callbackId: String
) {

    /**
     * 执行清理操作，按照依赖关系顺序释放资源，确保安全释放。
     *
     * 分两段：Activity 级释放（观察者、弹窗、返回键回调、各管理器、广播接收器、applock 监听）
     * 每次 onDestroy 都执行；通话会话级拆除只在通话真正结束时执行。
     *
     * @param isConfigurationChange 本次 onDestroy 源于配置变更导致的 Activity 重建，
     * 通话本身仍在继续，此时跳过会话级拆除
     */
    fun cleanup(
        lifecycleObserver: CallLifecycleObserver?,
        dialogManager: CallDialogManager?,
        proximityScreenOffController: ProximityScreenOffController?,
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
        viewModel: LCallViewModel,
        backPressedCallback: androidx.activity.OnBackPressedCallback?,
        isConfigurationChange: Boolean
    ) {
        L.i { "[Call] CallCleanupManager cleanup start isConfigurationChange:$isConfigurationChange" }

        // Activity-scoped releases. These run on every destroy, a configuration-change recreation
        // included: this Activity instance is going away either way, and its observer, dialogs,
        // receivers and app-lock listener would otherwise leak and then double up as soon as the
        // new instance registers its own.
        runCatching { cleanupLifecycleObserver(lifecycleObserver) }
            .onFailure { L.e(it) { "[Call] CallCleanupManager: cleanupLifecycleObserver failed" } }

        runCatching { cleanupUIResources(dialogManager, backPressedCallback) }
            .onFailure { L.e(it) { "[Call] CallCleanupManager: cleanupUIResources failed" } }

        runCatching {
            releaseManagers(
                proximityScreenOffController,
                pictureInPictureManager,
                callActivityBroadcastReceiver,
                screenUnlockBroadcastReceiver
            )
        }.onFailure { L.e(it) { "[Call] CallCleanupManager: releaseManagers failed" } }

        runCatching { cleanupListeners() }
            .onFailure { L.e(it) { "[Call] CallCleanupManager: cleanupListeners failed" } }

        // Call-session teardown — everything below ends the call. A configuration this Activity
        // does not declare in android:configChanges (font scale, locale, display size, layout
        // direction, navigation) only recreates it, while the call, the ViewModel and the
        // foreground service all survive; running these there stopped the service, cleared the
        // shared call state and broadcast a decline, i.e. silently hung up on an ordinary system
        // setting change. The guard lives here rather than in the manifest because configChanges
        // would have to enumerate every flag and would regress the moment a new one appeared.
        //
        // Skipping cleanupSystemResources is required, not merely safe: the participant
        // observation it stops is started once from the ViewModel, which survives the recreation
        // and would never restart it; and a ringing call must keep its ringtone.
        if (isConfigurationChange) {
            L.i { "[Call] CallCleanupManager cleanup end, call session kept across Activity recreation" }
            return
        }

        runCatching { cleanupSystemResources(vibrationManager, ringtoneManager, contactorCacheManager, callControlMessageManager) }
            .onFailure { L.e(it) { "[Call] CallCleanupManager: cleanupSystemResources failed" } }

        runCatching { stopService(serviceManager) }
            .onFailure { L.e(it) { "[Call] CallCleanupManager: stopService failed" } }

        runCatching { resetState(onGoingCallStateManager, callDataManager, viewModel) }
            .onFailure { L.e(it) { "[Call] CallCleanupManager: resetState failed" } }

        runCatching { sendDeclineCallBroadcast() }
            .onFailure { L.e(it) { "[Call] CallCleanupManager: sendDeclineCallBroadcast failed" } }

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
     * 释放各种管理器资源
     */
    private fun releaseManagers(
        proximityScreenOffController: ProximityScreenOffController?,
        pictureInPictureManager: PictureInPictureManager?,
        callActivityBroadcastReceiver: CallActivityBroadcastReceiver?,
        screenUnlockBroadcastReceiver: ScreenUnlockBroadcastReceiver?
    ) {
        // 释放传感器管理器
        proximityScreenOffController?.release()
        L.d { "[Call] CallCleanupManager released proximity screen-off controller" }

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
        contactorCacheManager.stopParticipantObservation()
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
     * 重置状态管理器
     */
    private fun resetState(
        onGoingCallStateManager: OnGoingCallStateManager,
        callDataManager: CallDataManager,
        viewModel: LCallViewModel
    ) {
        onGoingCallStateManager.reset()
        L.d { "[Call] CallCleanupManager reset state manager" }

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
        Intent(LCallActivity.ACTION_IN_CALLING_CONTROL).apply {
            putExtra(
                LCallActivity.EXTRA_CONTROL_TYPE,
                CallActionType.DECLINE.type
            )
            setPackage(context.packageName)
            context.sendBroadcast(this)
        }
        L.d { "[Call] CallCleanupManager sent decline call broadcast" }
    }
}

