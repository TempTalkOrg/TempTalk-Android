package com.difft.android.call.handler

import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.data.CallStatus
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.state.OnGoingCallStateManager
import kotlinx.coroutines.launch

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
     * 本次是否为主叫发起（isInitiator）。显式取自发起动作，只有 start-call 路径会置 START_CALL；
     * join/accept 走 JOIN_CALL/ACCEPT_CALL，原发起人二次入会同样恒为 false。
     * 刻意不从 [callRole]/[CallData] 反推——那会把原发起人二次入会误判成发起者。
     */
    private val isInitiator: Boolean
        get() = callIntent.action == CallIntent.Action.START_CALL

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

        // 主叫在信令尚未连上（窗口 W，仍处于 CALLING）时退出：不管点的是离开还是结束，
        // 都走结束逻辑——1v1 发 cancel，group 把 LEAVE 升级为 END（forceEnd 语义的 hangup）。
        // 此时 roomId 往往还没就绪，取消由 clientCallId 兜底定位（服务端就绪后打通）。
        if (isInitiator && viewModel.callStatus.value == CallStatus.CALLING) {
            L.i { "[Call] CallExitHandler: initiator exits before signaling connected, sending end signal" }
            handleInitiatorPreConnectExit(params)
            return
        }

        // 尝试获取通话列表中的通话信息
        val callInfo = callDataManager.getCallListData()[params.roomId]

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

        // CallData.type 是已生效的会议类型（由 CallTypeCoordinator 解析后回写），优先于 intent 带入的初始值：
        // 后者在类型解析完成前可能为空串，会把 instant 会议误判成 1v1。
        val effectiveType = callInfo.type.orEmpty().ifEmpty { params.callType }.ifEmpty { callType }

        // 根据通话状态执行不同的退出逻辑
        handleExitByStatus(params, effectiveType)
    }

    /**
     * 根据通话状态处理退出逻辑
     */
    private fun handleExitByStatus(params: CallExitParams, effectiveType: String) {
        when (val status = viewModel.callStatus.value) {
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
                handleConnectedExit(params, effectiveType)
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
            appScope.launch {
                callToChatController.cancelCall(
                    callerId = callIntent.callerId,
                    callRole = callRole,
                    type = callType,
                    roomId = roomId,
                    conversationId = conversationId
                )
                L.i { "[Call] CallExitHandler: Cancel call message sent, ending call" }
                onEndCall()
            }
        } ?: run {
            L.w { "[Call] CallExitHandler: No roomId available, ending call directly" }
            onEndCall()
        }
    }

    /**
     * 主叫在信令未连上（窗口 W）时退出：直接发结束信令并结束通话。
     * - 1v1：发 cancel（现有语义保持不变，只是不再受 roomId 守卫阻挡）。
     * - group：把 LEAVE 升级为 END，发 hangup（For.Group = end-for-all）。
     *
     * 不走 RTM sendEndCall——房间此刻尚未连上，RTM 通道不可用；结束信令走 HTTP
     * controlmessages 独立通道。roomId 可能为空，由 clientCallId 兜底（后端就绪后打通）。
     */
    private fun handleInitiatorPreConnectExit(params: CallExitParams) {
        // 第一时间（主线程同步，早于下面的 appScope.launch）标记取消意图：CallSessionStarter 若在被
        // teardown 取消前收到响应，会读到此门闩、确定性地跳过响铃/始通话消息，并 best-effort 补发权威取消。
        // 远端止铃的最终保证是后端把这里携带 clientCallId 的取消当作墓碑（后续用同一 clientCallId 建房不再
        // 响铃）——快速退出时响应往往晚于 teardown、补发不一定执行，故不能依赖客户端时序。
        onGoingCallStateManager.markInitiatorPreConnectCancelled()
        val roomId = onGoingCallStateManager.getCurrentRoomId()?.takeIf { it.isNotEmpty() }
            ?: params.roomId
            ?: ""
        val resolvedType = CallType.fromString(callType) ?: CallType.ONE_ON_ONE
        L.i { "[Call] CallExitHandler handleInitiatorPreConnectExit type:$resolvedType roomId:$roomId" }
        appScope.launch {
            if (resolvedType == CallType.ONE_ON_ONE) {
                callToChatController.cancelCall(
                    callerId = callIntent.callerId,
                    callRole = callRole,
                    type = callType,
                    roomId = roomId,
                    conversationId = conversationId,
                )
            } else {
                callToChatController.hangUpCall(
                    callerId = params.callerId,
                    callRole = callRole,
                    type = callType,
                    roomId = roomId,
                    conversationId = conversationId,
                    callUidList = viewModel.getCurrentCallUidList(),
                )
            }
            L.i { "[Call] CallExitHandler: pre-connect end signal sent, ending call" }
            onEndCall()
        }
    }

    /**
     * 处理 CONNECTED/RECONNECTED 状态的退出
     * 发送挂断消息
     */
    private fun handleConnectedExit(params: CallExitParams, effectiveType: String) {
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

        // 参会者 uid 必须在发出 end-call RTM 之前快照：远端收到该消息就会陆续退房，
        // 等到回调里再读，列表可能已经缩水甚至为空，对端就收不到挂断消息了。
        val callUidList = viewModel.getCurrentCallUidList()

        viewModel.rtm.sendEndCall(onComplete = {
            // instant 会议没有归属会话，收件人由参会者 uid 列表解析，conversationId 恒为空；
            // 1v1 / 群的收件人则由 conversationId 定位（1v1 主叫更是直接非空断言），缺失时只能就地结束。
            if (effectiveType != CallType.INSTANT.type && conversationId.isNullOrEmpty()) {
                L.w { "[Call] CallExitHandler: conversationId is missing for callType:$effectiveType, ending call directly" }
                onEndCall()
                return@sendEndCall
            }

            appScope.launch {
                callToChatController.hangUpCall(
                    callerId = params.callerId,
                    callRole = callRole,
                    type = effectiveType,
                    roomId = roomId,
                    conversationId = conversationId,
                    callUidList = callUidList,
                )
                L.i { "[Call] CallExitHandler: Hang up call message sent, ending call" }
                onEndCall()
            }
        })
    }
}
