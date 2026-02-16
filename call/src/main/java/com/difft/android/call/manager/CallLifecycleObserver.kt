package com.difft.android.call.manager

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.AutoLeave
import com.difft.android.base.user.CallConfig
import com.difft.android.call.LCallToChatController
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.CallStatus
import com.difft.android.call.handler.CallErrorHandler
import com.difft.android.call.state.OnGoingCallStateManager
import kotlinx.coroutines.launch

/**
 * 统一管理通话相关的生命周期状态监听
 * 负责在 Activity STARTED 状态下收集和响应 ViewModel 的状态变化
 */
class CallLifecycleObserver(
    private val viewModel: LCallViewModel,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val callToChatController: LCallToChatController,
    private val callErrorHandler: CallErrorHandler?,
    private val callDialogManager: CallDialogManager?,
    private val callConfig: CallConfig
) : DefaultLifecycleObserver {

    /**
     * 开始观察 ViewModel 的状态变化
     * 在 Activity STARTED 时开始收集 Flow
     */
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    observeErrorState()
                }
                launch {
                    observeScreenSharingState()
                }
                launch {
                    observeCallStatus()
                }
                launch {
                    observeNoSpeakTimeout()
                }
            }
        }
    }

    /**
     * 观察错误状态
     * 当 ViewModel 发生错误时，委托给 CallErrorHandler 处理
     */
    private suspend fun observeErrorState() {
        viewModel.error.collect { error ->
            error?.let {
                callErrorHandler?.handleError(it)
            }
        }
    }

    /**
     * 观察屏幕分享状态
     * 当用户开始或停止屏幕分享时，更新状态管理器
     */
    private suspend fun observeScreenSharingState() {
        viewModel.callUiController.isShareScreening.collect { isSharing ->
            L.i { "[Call] CallLifecycleObserver, viewModel.isParticipantSharedScreen: $isSharing" }
            onGoingCallStateManager.setIsInScreenSharing(isSharing)
        }
    }

    /**
     * 观察通话状态
     * 当通话状态变为 CONNECTED 时，取消通知
     */
    private suspend fun observeCallStatus() {
        viewModel.callStatus.collect { status ->
            L.i { "[Call] CallLifecycleObserver callStatus:$status" }
            if (status == CallStatus.CONNECTED) {
                onGoingCallStateManager.getCurrentRoomId()?.let { roomId ->
                    callToChatController.cancelNotificationById(roomId.hashCode())
                }
            }
        }
    }

    /**
     * 观察无说话超时状态
     * 当检测到无说话超时时，显示或关闭通话结束提醒对话框
     */
    private suspend fun observeNoSpeakTimeout() {
        viewModel.isNoSpeakSoloTimeout.collect { isTimeout ->
            if (isTimeout) {
                handleNoSpeakTimeout()
            } else {
                dismissNoSpeakTimeout()
            }
        }
    }

    /**
     * 处理无说话超时
     * 显示通话结束提醒对话框
     */
    private fun handleNoSpeakTimeout() {
        if (callDialogManager?.isShowingCallingEndReminder() == false) {
            L.i { "[Call] CallLifecycleObserver Show CallingEnd Reminder" }
            val waitingSeconds = callConfig.autoLeave?.runAfterReminderTimeout 
                ?: AutoLeave().runAfterReminderTimeout
            val secondsToLeaveMeeting = waitingSeconds / 1000
            callDialogManager.showCallingEndReminder(secondsToLeaveMeeting)
        }
    }

    /**
     * 关闭无说话超时提醒
     * 关闭通话结束提醒对话框
     */
    private fun dismissNoSpeakTimeout() {
        if (callDialogManager?.isShowingCallingEndReminder() == true) {
            L.i { "[Call] CallLifecycleObserver dismiss CallingEnd Reminder" }
            callDialogManager.dismissCallingEndReminder()
        }
    }
}

