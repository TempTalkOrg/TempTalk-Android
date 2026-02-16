package com.difft.android.call.handler

import android.content.Intent
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallRole
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.application
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.manager.CallRingtoneManager
import com.difft.android.call.service.ForegroundService
import com.difft.android.call.state.OnGoingCallStateManager

/**
 * 统一管理通话动作处理逻辑
 * 负责处理各种通话控制动作（拒绝、挂断、结束等）
 */
class CallActionHandler(
    private val viewModel: LCallViewModel,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val callRingtoneManager: CallRingtoneManager,
    private val callIntent: CallIntent,
    private val callRole: CallRole,
    private val conversationId: String?,
    private val onExitClick: (CallExitParams) -> Unit,
    private val onEndCall: () -> Unit,
    private val onShowTip:(message: String, onDismiss: () -> Unit) ->Unit
) {
    /**
     * 处理通话动作
     *
     * @param actionType 动作类型
     * @param roomId 房间 ID
     */
    fun handleCallAction(actionType: String, roomId: String) {
        L.i { "[Call] CallActionHandler handleCallAction actionType:$actionType roomId:$roomId" }

        if (actionType == CallActionType.DECLINE.type) {
            handleDeclineAction()
            return
        }

        if (shouldProcessCallAction(roomId)) {
            handleCallEndActions(actionType)
        }
    }

    /**
     * 处理拒绝动作
     * 创建退出参数并触发退出流程
     */
    private fun handleDeclineAction() {
        onGoingCallStateManager.getCurrentRoomId()?.let { currentRoomId ->
            val callExitParams = CallExitParams(
                roomId = currentRoomId,
                callerId = callIntent.callerId,
                callRole = callRole,
                callType = viewModel.callType.value,
                conversationId = conversationId
            )
            onExitClick(callExitParams)
        }
    }

    /**
     * 判断是否应该处理通话动作
     * 只有在通话未结束、正在通话中且房间 ID 匹配时才处理
     */
    private fun shouldProcessCallAction(roomId: String): Boolean {
        return !onGoingCallStateManager.isInCallEnding() &&
               onGoingCallStateManager.isInCalling() &&
               roomId == onGoingCallStateManager.getCurrentRoomId()
    }

    /**
     * 处理通话结束相关动作
     * 包括 CALLEND、REJECT、HANGUP
     */
    private fun handleCallEndActions(actionType: String) {
        onGoingCallStateManager.setIsInCallEnding(true)

        when (actionType) {
            CallActionType.CALLEND.type, CallActionType.REJECT.type -> {
                handleCallEndOrReject(actionType)
            }
            CallActionType.HANGUP.type -> {
                handleHangup()
            }
        }
    }

    /**
     * 处理通话结束或拒绝动作
     * 停止服务、停止铃声、播放挂断音效、显示提示
     */
    private fun handleCallEndOrReject(actionType: String) {
        stopOngoingCallService()

        if (shouldStopCallerRingTone()) {
            callRingtoneManager.stopRingTone(tag = "reject: callee reject the call")
        }

        callRingtoneManager.playHangupRingTone()

        val messageTip = if (actionType == CallActionType.REJECT.type) {
            R.string.call_callee_action_reject
        } else {
            R.string.call_callee_action_hangup
        }

        onShowTip(ResUtils.getString(messageTip), onEndCall)
    }

    /**
     * 处理挂断动作
     * 停止服务、播放挂断音效、显示提示
     */
    private fun handleHangup() {
        stopOngoingCallService()
        callRingtoneManager.playHangupRingTone()
        onShowTip(ResUtils.getString(R.string.call_callee_action_hangup), onEndCall)
    }

    /**
     * 判断是否应该停止主叫铃声
     * 只有在主叫且通话时长为 0 时才停止
     */
    private fun shouldStopCallerRingTone(): Boolean {
        return callRole == CallRole.CALLER &&
               viewModel.timerManager.callDurationSeconds.value == 0L
    }

    /**
     * 停止正在进行的通话服务
     */
    private fun stopOngoingCallService() {
        if (!ForegroundService.Companion.isServiceRunning) {
            return
        }
        L.i { "[Call] CallActionHandler stopOngoingCallService" }
        try {
            val serviceIntent = Intent(application, ForegroundService::class.java)
            application.stopService(serviceIntent)
        } catch (e: Exception) {
            L.e { "[Call] CallActionHandler Failed to stop ongoing call service: ${e.message}" }
        }
    }

}