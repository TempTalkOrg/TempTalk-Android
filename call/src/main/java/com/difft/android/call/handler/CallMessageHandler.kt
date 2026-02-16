package com.difft.android.call.handler

import android.content.Intent
import android.text.TextUtils
import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallDataSourceType
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallToChatController
import com.difft.android.call.R
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.response.RoomState
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.group.GroupRepo
import difft.android.messageserialization.For
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话消息处理器
 * 负责处理从 WebSocket 接收到的各种通话消息（Calling, Joined, Cancel, Reject, Hangup）
 */
@Singleton
class CallMessageHandler @Inject constructor(
    @param:ChativeHttpClientModule.Call
    private val callHttpClient: ChativeHttpClient,
    private val callDataManager: CallDataManager,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val inComingCallStateManager: InComingCallStateManager,
    private val contactorCacheManager: ContactorCacheManager,
    private val callToChatController: LCallToChatController,
    private val groupRepo: GroupRepo,
    private val dbRoomStore: DBRoomStore,
) {
    private val callService: LCallHttpService by lazy {
        callHttpClient.getService(LCallHttpService::class.java)
    }

    private val mySelfId: String by lazy {
        globalServices.myId
    }

    // 常量定义
    companion object {
        private const val RESPONSE_STATUS_SUCCESS = 0
    }

    /**
     * 处理通话消息的主入口
     */
    fun handleCallMessage(message: SignalServiceDataClass) {
        val envelope = message.signalServiceEnvelope
        val content = message.signalServiceContent?.callMessage
        val roomId = envelope.roomId

        L.d { "[Call] handleCallMessage, envelope.content:${content}" }
        L.i { "[Call] handleCallMessage, receive call message envelope.timestamp:${envelope.timestamp} roomId:${roomId}" }

        if (roomId.isNullOrEmpty()) {
            L.e { "[Call] handleCallMessage, envelope roomId is Null Or Empty" }
            return
        }

        when {
            content?.hasCalling() == true -> handleCallingMessage(message, envelope, content, roomId)
            content?.hasJoined() == true -> handleJoinedMessage(content, roomId)
            content?.hasCancel() == true -> handleCancelMessage(content, roomId)
            content?.hasReject() == true -> handleRejectMessage(message, content, roomId)
            content?.hasHangup() == true -> handleHangupMessage(content, roomId)
        }
    }

    /**
     * 处理 Calling 消息（START/INVITE）
     */
    private fun handleCallingMessage(
        message: SignalServiceDataClass,
        envelope: SignalServiceProtos.Envelope,
        content: SignalServiceProtos.CallMessage,
        roomId: String
    ) {
        L.i { "[Call] handleCallMessage, has calling message envelope.timestamp:${envelope.timestamp}" }

        if (TextUtils.isEmpty(roomId)) {
            L.e { "[Call] handleCallMessage, roomId is empty" }
            return
        }

        L.i { "[Call] handleCallMessage, start checkCall roomId: $roomId" }

        appScope.launch(Dispatchers.IO) {
            try {
                val response = callService.checkCall(SecureSharedPrefsUtil.getToken(), roomId)
                    .await()

                if (response.status != RESPONSE_STATUS_SUCCESS || response.data?.userStopped == true) {
                    L.e { "[Call] handleCallMessage, checkCall fail:${response.reason}" }
                    return@launch
                }

                L.i { "[Call] handleCallMessage, checkCall success" }

                processCallingMessage(message, envelope, content, roomId, response.data)
            } catch (error: Exception) {
                L.e { "[Call] handleCallMessage, checkCall fail:${error.stackTraceToString()}" }
            }
        }
    }

    /**
     * 处理 Calling 消息的核心逻辑
     */
    private suspend fun processCallingMessage(
        message: SignalServiceDataClass,
        envelope: SignalServiceProtos.Envelope,
        content: SignalServiceProtos.CallMessage,
        roomId: String,
        checkCallData: RoomState?
    ) {
        val callerId = content.calling?.caller ?: message.senderId

        if (envelope.source != callerId) {
            L.e { "[Call] handleCallMessage, checkCall source is not callerId" }
            return
        }

        val callInfo = resolveCallInfo(content.calling, callerId)
        if (content.calling.createCallMsg) {
            handleCallTextMessage(envelope, content, callInfo)
        }

        handleCallData(roomId, callInfo, envelope, checkCallData, message.senderId, content)
    }

    /**
     * 解析通话信息（类型、会话ID、名称）
     */
    private suspend fun resolveCallInfo(
        calling: SignalServiceProtos.CallMessage.Calling,
        callerId: String
    ): CallInfo {
        var callType: CallType
        var conversationId: String? = null
        var callName = calling.roomName ?: ""

        when {
            calling.hasConversationId() && calling.conversationId.hasNumber() -> {
                // 一对一通话
                callType = CallType.ONE_ON_ONE
                conversationId = calling.conversationId.number
                contactorCacheManager.getDisplayName(callerId)?.takeIf { it.isNotEmpty() }?.let {
                    callName = it
                }
            }
            calling.hasConversationId() && calling.conversationId.hasGroupId() -> {
                // 群组通话
                conversationId = calling.conversationId.groupId.toStringUtf8()
                val inGroup = checkUserIsInGroup(mySelfId, conversationId)
                if (inGroup) {
                    callType = CallType.GROUP
                    getGroupNameSafely(conversationId)?.takeIf { it.isNotEmpty() }?.let {
                        callName = it
                    }
                } else {
                    // 不在群组中，转为即时通话
                    callType = CallType.INSTANT
                    conversationId = null
                    callName = getInstantCallName(callerId)
                }
            }
            else -> {
                // 即时通话
                callType = CallType.INSTANT
                callName = getInstantCallName(callerId)
            }
        }

        return CallInfo(callType, conversationId, callName)
    }

    /**
     * 获取即时通话名称
     */
    private suspend fun getInstantCallName(callerId: String): String {
        val displayName = contactorCacheManager.getDisplayName(callerId)
        return if (!displayName.isNullOrEmpty()) {
            "${displayName}${ApplicationHelper.instance.getString(R.string.call_instant_call_title)}"
        } else {
            ApplicationHelper.instance.getString(R.string.call_instant_call_title_default)
        }
    }

    /**
     * 处理通话文本消息
     */
    private suspend fun handleCallTextMessage(
        envelope: SignalServiceProtos.Envelope,
        content: SignalServiceProtos.CallMessage,
        callInfo: CallInfo
    ) {
        val callerId = content.calling?.caller ?: ""
        val textContent = generateCallTextContent(content.calling, callInfo.callType, callerId)

        when (content.calling.controlType) {
            CallActionType.START.type -> handleStartCallTextMessage(
                envelope, content, callInfo, textContent, callerId
            )
            CallActionType.INVITE.type -> handleInviteCallTextMessage(
                envelope, content, callInfo, textContent, callerId
            )
        }
    }

    /**
     * 生成通话文本消息内容
     */
    private suspend fun generateCallTextContent(
        calling: SignalServiceProtos.CallMessage.Calling,
        callType: CallType,
        callerId: String
    ): String {
        return when (calling.controlType) {
            CallActionType.START.type -> {
                if (callType == CallType.GROUP) {
                    ApplicationHelper.instance.getString(
                        R.string.call_group_send_message,
                        contactorCacheManager.getDisplayName(callerId)
                    )
                } else {
                    ApplicationHelper.instance.getString(R.string.call_1v1_send_message)
                }
            }
            else -> {
                ApplicationHelper.instance.getString(
                    R.string.call_invite_send_message,
                    contactorCacheManager.getDisplayName(callerId)
                )
            }
        }
    }

    /**
     * 处理 START 通话的文本消息
     */
    private fun handleStartCallTextMessage(
        envelope: SignalServiceProtos.Envelope,
        content: SignalServiceProtos.CallMessage,
        callInfo: CallInfo,
        textContent: String,
        callerId: String
    ) {
        val isSelfSync = envelope.source == mySelfId && envelope.sourceDevice != DEFAULT_DEVICE_ID

        if (isSelfSync) {
            // 处理自己一端的同步call消息
            callInfo.conversationId?.let { otherSideId ->
                val forWhat = when (callInfo.callType) {
                    CallType.GROUP -> For.Group(otherSideId)
                    else -> For.Account(otherSideId)
                }
                callToChatController.sendOrCreateCallTextMessage(
                    CallActionType.JOINED, textContent, envelope.sourceDevice,
                    content.calling.timestamp, envelope.systemShowTimestamp,
                    For.Account(callerId), forWhat, callInfo.callType, true
                )
            } ?: run {
                L.e { "[Call] handleCallMessage conversationId is null" }
            }
        } else {
            // 处理对方发来的call消息
            val forWhat = when (callInfo.callType) {
                CallType.GROUP -> callInfo.conversationId?.let { For.Group(it) }
                else -> For.Account(callerId)
            }
            forWhat?.let {
                callToChatController.sendOrCreateCallTextMessage(
                    CallActionType.JOINED, textContent, envelope.sourceDevice,
                    content.calling.timestamp, envelope.systemShowTimestamp,
                    For.Account(callerId), it, callInfo.callType, true
                )
            } ?: run {
                L.e { "[Call] handleCallMessage forWhat is null" }
            }
        }
    }

    /**
     * 处理 INVITE 通话的文本消息
     */
    private fun handleInviteCallTextMessage(
        envelope: SignalServiceProtos.Envelope,
        content: SignalServiceProtos.CallMessage,
        callInfo: CallInfo,
        textContent: String,
        callerId: String
    ) {
        val isSelfSync = envelope.source == mySelfId && envelope.sourceDevice != DEFAULT_DEVICE_ID
        val inviteeList = content.calling.calleesList ?: return

        if (isSelfSync) {
            // 处理自己一端的同步call消息
            inviteeList.forEachIndexed { index, invitee ->
                val callMessageTime = content.calling.timestamp + index
                callToChatController.sendOrCreateCallTextMessage(
                    CallActionType.JOINED, textContent, envelope.sourceDevice,
                    callMessageTime, envelope.systemShowTimestamp,
                    For.Account(callerId), For.Account(invitee), callInfo.callType, true
                )
            }
        } else {
            // 处理对方发来的invite消息
            val index = inviteeList.indexOf(mySelfId)
            if (index >= 0) {
                val callMessageTime = content.calling.timestamp + index
                callToChatController.sendOrCreateCallTextMessage(
                    CallActionType.JOINED, textContent, envelope.sourceDevice,
                    callMessageTime, envelope.systemShowTimestamp,
                    For.Account(callerId), For.Account(callerId), callInfo.callType, true
                )
            }
        }
    }

    /**
     * 处理通话数据
     */
    private suspend fun handleCallData(
        roomId: String,
        callInfo: CallInfo,
        envelope: SignalServiceProtos.Envelope,
        checkCallData: RoomState?,
        senderId: String,
        content: SignalServiceProtos.CallMessage
    ) {
        val existingCall = callDataManager.getCallListData()[roomId]
        val shouldAddCallData = existingCall == null ||
                (existingCall.source != CallDataSourceType.MESSAGE &&
                        roomId != onGoingCallStateManager.getCurrentRoomId())

        if (shouldAddCallData) {
            val callerId = content.calling?.caller ?: senderId
            val callData = createCallData(
                roomId, callInfo, envelope, callerId, checkCallData?.createdAt
            )
            L.d { "[Call] handleCallMessage, Calling addCallData:$callData" }
            callDataManager.addCallData(callData)

            checkCallData?.anotherDeviceJoined?.let { anotherDeviceJoined ->
                if (checkIfShowIncomingCall(anotherDeviceJoined, senderId, callData)) {
                    showIncomingNotificationOrActivity(callData)
                }
            }
        } else if (content.calling.controlType == CallActionType.INVITE.type) {
            showIncomingNotificationOrActivity(existingCall)
        }
    }

    /**
     * 创建通话数据对象
     */
    private fun createCallData(
        roomId: String,
        callInfo: CallInfo,
        envelope: SignalServiceProtos.Envelope,
        callerId: String,
        createdAt: Long?
    ): CallData {
        val conversation = when (callInfo.callType) {
            CallType.GROUP -> callInfo.conversationId
            CallType.ONE_ON_ONE -> {
                if (envelope.source == mySelfId && envelope.sourceDevice != DEFAULT_DEVICE_ID) {
                    callInfo.conversationId
                } else {
                    callerId
                }
            }
            CallType.INSTANT -> null
        }

        return CallData(
            type = callInfo.callType.type,
            version = 0,
            createdAt = createdAt ?: System.currentTimeMillis(),
            roomId = roomId,
            caller = CallDataCaller(callerId, envelope.sourceDevice),
            conversation = conversation,
            encMeta = null,
            callName = callInfo.callName,
            source = CallDataSourceType.MESSAGE
        )
    }

    /**
     * 处理 Joined 消息
     */
    private fun handleJoinedMessage(
        content: SignalServiceProtos.CallMessage,
        roomId: String
    ) {
        L.i { "[Call] handleCallMessage, hasJoined Message:${content.joined.roomId}" }

        content.joined?.roomId ?: return

        cancelNotificationAndHandleService(roomId, CallActionType.JOINED, "joined: other device joined the call")

        // 更新通话数据
        callDataManager.getCallListData().let { callingData ->
            callingData[roomId]?.hasAnotherDeviceJoined = true
            callDataManager.updateCallingListData(callingData)
        }

        // 清除会话列表 critical alert 高亮状态
        appScope.launch(Dispatchers.IO) {
            dismissCriticalAlertForRoom(roomId)
        }
    }

    /**
     * 处理 Cancel 消息
     */
    private fun handleCancelMessage(
        content: SignalServiceProtos.CallMessage,
        roomId: String
    ) {
        L.i { "[Call] handleCallMessage, hasCancel Message:${content.cancel.roomId}" }

        content.cancel?.roomId ?: return

        cancelNotificationAndHandleService(roomId, CallActionType.CANCEL, "cancel: caller cancel the call")
    }

    /**
     * 处理 Reject 消息
     */
    private fun handleRejectMessage(
        message: SignalServiceDataClass,
        content: SignalServiceProtos.CallMessage,
        roomId: String
    ) {
        L.i { "[Call] handleCallMessage, hasRejected Message:${content.reject.roomId}" }

        content.reject?.roomId ?: return

        if (TextUtils.isEmpty(roomId)) return

        // 如果是自己另一端reject会，而自己本地已经入会，则本地忽略自己另一端的reject
        if (shouldIgnoreSelfReject(message, roomId)) {
            L.i { "[Call] handleCallMessage reject, remote myself device reject, but local has in meeting." }
            return
        }

        // 取消入会通知
        callToChatController.cancelNotificationById(roomId.hashCode())

        // 移除callData缓存数据，如果是群call则不移除
        removeCallDataIfOneOnOne(roomId)

        // 处理会议呼叫：本地主叫，被叫拒绝入会时发送的reject消息
        if (onGoingCallStateManager.isInCalling() &&
            onGoingCallStateManager.getCurrentRoomId() == roomId
        ) {
            updateControlMessage(CallActionType.REJECT, roomId)
        }

        // 处理会议邀请：本地被叫，自己另一端设备拒绝入会时发送的reject消息
        cancelNotificationAndHandleService(roomId, CallActionType.REJECT, "reject: your other device reject the call")
    }

    /**
     * 处理 Hangup 消息
     */
    private fun handleHangupMessage(
        content: SignalServiceProtos.CallMessage,
        roomId: String
    ) {
        L.i { "[Call] handleCallMessage, hasHangup Message:${content.hangup.roomId}" }

        content.hangup?.roomId ?: return

        if (TextUtils.isEmpty(roomId)) return

        if (onGoingCallStateManager.isInCalling() &&
            onGoingCallStateManager.getCurrentRoomId() == roomId
        ) {
            val controlMessage = OnGoingCallStateManager.ControlMessage(
                actionType = CallActionType.HANGUP,
                roomId = roomId
            )
            onGoingCallStateManager.updateControlMessage(controlMessage)
        }
        callDataManager.removeCallData(roomId)
    }

    fun handleCallEndNotification(roomId: String) {
        if (roomId.isEmpty()) return

        callDataManager.removeCallData(roomId)
        callToChatController.cancelNotificationById(roomId.hashCode())

        cancelNotificationAndHandleService(roomId, CallActionType.CALLEND, "ended: server call end notification")

        if (onGoingCallStateManager.isInCalling() && onGoingCallStateManager.getCurrentRoomId() == roomId) {
            updateControlMessage(CallActionType.CALLEND, roomId)
        }
    }

    /**
     * 通话信息数据类
     */
    private data class CallInfo(
        val callType: CallType,
        val conversationId: String?,
        val callName: String
    )

    /**
     * 取消通知并处理服务（更新控制消息或停止来电服务）
     */
    private fun cancelNotificationAndHandleService(
        roomId: String,
        actionType: CallActionType,
        tag: String
    ) {
        callToChatController.cancelNotificationById(roomId.hashCode())

        if (inComingCallStateManager.isActivityShowing()) {
            updateControlMessage(actionType, roomId)
        } else {
            LCallManager.stopIncomingCallService(roomId, tag = tag)
        }
    }

    /**
     * 更新控制消息
     */
    private fun updateControlMessage(actionType: CallActionType, roomId: String) {
        val controlMessage = OnGoingCallStateManager.ControlMessage(
            actionType = actionType,
            roomId = roomId
        )
        onGoingCallStateManager.updateControlMessage(controlMessage)
    }

    /**
     * 清除会话列表 critical alert 高亮状态
     */
    private suspend fun dismissCriticalAlertForRoom(roomId: String) {
        callDataManager.getCallData(roomId)?.let { data ->
            when (data.type) {
                CallType.INSTANT.type -> {
                    data.caller.uid?.let { uid ->
                        dbRoomStore.clearCriticalAlert(uid)
                        callToChatController.cancelCriticalAlertNotification(uid)
                        LCallManager.dismissCriticalAlert(uid)
                    }
                }
                CallType.ONE_ON_ONE.type -> {
                    data.conversation?.let { conversationId ->
                        dbRoomStore.clearCriticalAlert(conversationId)
                        callToChatController.cancelCriticalAlertNotification(conversationId)
                        LCallManager.dismissCriticalAlert(conversationId)
                    }
                }
                CallType.GROUP.type -> {
                    data.conversation?.let { conversationId ->
                        val isInGroup = checkUserIsInGroup(mySelfId, conversationId)
                        if (isInGroup) {
                            dbRoomStore.clearCriticalAlert(conversationId)
                            callToChatController.cancelCriticalAlertNotification(conversationId)
                            LCallManager.dismissCriticalAlert(conversationId)
                        } else {
                            data.caller.uid?.let { uid ->
                                dbRoomStore.clearCriticalAlert(uid)
                                callToChatController.cancelCriticalAlertNotification(uid)
                                LCallManager.dismissCriticalAlert(uid)
                            }
                        }
                    }
                }
            }
        }?: run {
            L.w { "[Call] handleCallMessage joined: call data is null for roomId=$roomId, cannot dismiss critical alert" }
        }

    }

    /**
     * 判断是否应该忽略自己的 reject 消息
     */
    private fun shouldIgnoreSelfReject(message: SignalServiceDataClass, roomId: String): Boolean {
        return message.senderId == mySelfId &&
                onGoingCallStateManager.isInCalling() &&
                onGoingCallStateManager.getCurrentRoomId() == roomId
    }

    /**
     * 如果是群call则不移除，只移除一对一通话的 callData
     */
    private fun removeCallDataIfOneOnOne(roomId: String) {
        val callDataList = callDataManager.getCallListData()
        if (callDataList.containsKey(roomId)) {
            callDataList[roomId]?.let {
                if (it.type == CallType.ONE_ON_ONE.type) {
                    callDataManager.removeCallData(roomId)
                }
            }
        }
    }

    /**
     * 检查用户是否在群组中
     */
    private suspend fun checkUserIsInGroup(userId: String, gid: String): Boolean {
        return try {
            val resp = groupRepo.getGroupInfo(gid).await()

            val members = resp.data?.members.orEmpty()
            members.any { it.uid == userId }
        } catch (e: Exception) {
            L.e { "[Call] CallMessageHandler checkUserIsInGroup error: ${e.stackTraceToString()}" }
            false
        }
    }

    /**
     * 安全获取群组名称
     */
    private suspend fun getGroupNameSafely(conversationId: String): String? {
        return try {
            val groupInfoOptional = callToChatController.getSingleGroupInfo(application, conversationId)
            if (groupInfoOptional.isPresent) {
                groupInfoOptional.get().name
            } else {
                null
            }
        } catch (e: Exception) {
            L.e { "[Call] CallMessageHandler getGroupNameSafely error: ${e.stackTraceToString()}" }
            null
        }
    }

    /**
     * 检查是否显示来电
     */
    private fun checkIfShowIncomingCall(anotherDeviceJoined: Boolean, msgSenderId: String, callData: CallData): Boolean {
        // 处理会议邀请：自己另一端没有入会、且call消息发起人不是自己、且不是当前正在进行中的会议
        return !anotherDeviceJoined &&
                msgSenderId != mySelfId &&
                !callToChatController.isIncomingCallNotifying(callData.roomId) &&
                onGoingCallStateManager.getCurrentRoomId() != callData.roomId
    }

    /**
     * 显示来电通知或活动
     */
    private suspend fun showIncomingNotificationOrActivity(callData: CallData) = withContext(Dispatchers.Main) {
        val intentNotify = Intent().apply {
            putExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE, callData.type)
            putExtra(LCallConstants.BUNDLE_KEY_CALL_ROLE, CallRole.CALLEE.type)
            putExtra(LCallConstants.BUNDLE_KEY_ROOM_ID, callData.roomId)
            putExtra(LCallConstants.BUNDLE_KEY_CALL_NAME, callData.callName)
            putExtra(LCallConstants.BUNDLE_KEY_CALLER_ID, callData.caller.uid)
            putExtra(LCallConstants.BUNDLE_KEY_CONVERSATION_ID, callData.conversation)
        }
        LCallManager.startIncomingCallService(intentNotify)
    }
}

