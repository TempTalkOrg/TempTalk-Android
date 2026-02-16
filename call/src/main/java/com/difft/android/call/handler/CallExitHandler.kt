package com.difft.android.call.handler

import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.data.CallStatus
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.state.OnGoingCallStateManager

/**
 * 统一管理通话退出逻辑
 * 负责根据不同的通话状态执行相应的退出操作
 */
class CallExitHandler(
    private val viewModel: LCallViewModel,
    private val callToChatController: LCallToChatController,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val callDataManager: CallDataManager,
    private val callIntent: CallIntent,
    private val callRole: CallRole,
    private val conversationId: String?,
    private val callType: String,
    private val onEndCall: () -> Unit
) {
    /**
     * 处理退出逻辑
     * @param params 退出参数
     * @param callEndType 退出类型（LEAVE 或 END）
     */
    fun handleExit(
        params: CallExitParams,
        callEndType: CallEndType = CallEndType.LEAVE
    ) {
        L.i { "[Call] CallExitHandler handleExit roomId:${params.roomId}, callEndType:$callEndType" }

        // 尝试获取通话列表中的通话信息
        val callInfo = callDataManager.getCallListData()?.get(params.roomId)

        // 如果通话信息不存在，或会议重连中，则直接结束通话并清理资源
        if (callInfo == null || viewModel.callStatus.value == CallStatus.RECONNECTING) {
            L.i { "[Call] CallExitHandler: Call info is null or reconnecting, ending call directly" }
            onEndCall()
            return
        }

        // 检查通话类型，如果是非1v1通话且是离开操作，则直接结束通话并清理资源
        if (callInfo.type != CallType.ONE_ON_ONE.type && callEndType == CallEndType.LEAVE) {
            L.i { "[Call] CallExitHandler: Non-1v1 call with LEAVE type, ending call directly" }
            onEndCall()
            return
        }

        // 根据通话状态执行不同的退出逻辑
        handleExitByStatus(params)
    }

    /**
     * 根据通话状态处理退出逻辑
     */
    private fun handleExitByStatus(params: CallExitParams) {
        val status = viewModel.callStatus.value
        when (status) {
            CallStatus.CALLING -> {
                // 主叫：发送取消消息
                handleCallingExit()
            }

            CallStatus.JOINING -> {
                // 被叫或其他角色：直接结束通话
                L.i { "[Call] CallExitHandler: Status is JOINING, ending call directly" }
                onEndCall()
            }

            CallStatus.CONNECTED, CallStatus.RECONNECTED -> {
                // 通话已连接：发送挂断消息
                handleConnectedExit(params)
            }

            else -> {
                // 其他状态：直接结束通话并清理资源
                L.i { "[Call] CallExitHandler: Status is $status, ending call directly" }
                onEndCall()
            }
        }
    }

    /**
     * 处理 CALLING 状态的退出
     * 发送取消消息
     */
    private fun handleCallingExit() {
        L.d { "[Call] CallExitHandler sendCancelCallMessage" }
        onGoingCallStateManager.getCurrentRoomId()?.let { roomId ->
            callToChatController.cancelCall(
                callerId = callIntent.callerId,
                callRole = callRole,
                type = callType,
                roomId = roomId,
                conversationId = conversationId
            ) {
                // 取消回调（结束后清理资源）
                L.i { "[Call] CallExitHandler: Cancel call message sent, ending call" }
                onEndCall()
            }
        } ?: run {
            // 如果没有 roomId，直接结束
            L.w { "[Call] CallExitHandler: No roomId available, ending call directly" }
            onEndCall()
        }
    }

    /**
     * 处理 CONNECTED/RECONNECTED 状态的退出
     * 发送挂断消息
     */
    private fun handleConnectedExit(params: CallExitParams) {
        val roomId = params.roomId
        if (roomId.isNullOrEmpty()) {
            L.w { "[Call] CallExitHandler: RoomId is null or empty, ending call directly" }
            onEndCall()
            return
        }

        // 移除通话数据
//        LCallManager.removeCallData(roomId)
        callDataManager.removeCallData(roomId)
        L.i { "[Call] CallExitHandler send hangUpCall CallMessage roomId:$roomId" }

        // 发送 RTM 结束通话消息
        viewModel.rtm.sendEndCall(onComplete = {
            // 验证参数
            if (roomId.isEmpty() || conversationId.isNullOrEmpty()) {
                L.w { "[Call] CallExitHandler: Invalid params, ending call directly" }
                onEndCall()
                return@sendEndCall
            }

            // 发送挂断消息到聊天
            callToChatController.hangUpCall(
                callerId = params.callerId,
                callRole = callRole,
                type = params.callType.ifEmpty { callType },
                roomId = roomId,
                conversationId = conversationId,
                callUidList = viewModel.getCurrentCallUidList(),
                onComplete = {
                    L.i { "[Call] CallExitHandler: Hang up call message sent, ending call" }
                    onEndCall()
                }
            )
        })
    }
}