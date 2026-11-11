package com.difft.android.call.handler


import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallDataSourceType
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.appScope
import com.difft.android.call.R
import com.difft.android.call.response.RoomState
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.whispersystems.signalservice.internal.push.SignalServiceProtos








internal data class CallInfo(
    val callType: CallType,
    val conversationId: String?,
    val callName: String,
)








internal fun CallMessageHandler.handleCallingMessage(
    message: SignalServiceDataClass,
    envelope: SignalServiceProtos.Envelope,
    content: SignalServiceProtos.CallMessage,
    roomId: String,
    admission: MessagingTicket,
    fromServerPush: Boolean,
) {
    L.i { "[Call] handleCallMessage, has calling message envelope.timestamp:${envelope.timestamp}" }
    L.i { "[Call] handleCallMessage, start checkCall roomId: $roomId" }

    
    val held = admission.retain("call-calling")
    appScope.launch(Dispatchers.IO) {
        try {
            val callerId = content.calling?.caller ?: message.senderId
            if (envelope.source != callerId) {
                L.e { "[Call] handleCallMessage, source is not callerId" }
                return@launch
            }

            val callInfo = resolveCallInfo(content.calling, callerId)

            
            
            
            
            
            if (content.calling.createCallMsg) {
                handleCallTextMessage(envelope, content, callInfo)
            }
            held?.close()

            
            
            if (!messagingGate.isOpen) {
                L.i { "[Call] event=calling_live_skipped reason=messaging_paused roomId=$roomId" }
                return@launch
            }

            val response = callService.checkCall(roomId)
            if (response.status != CallMessageHandler.RESPONSE_STATUS_SUCCESS ||
                response.data?.userStopped == true
            ) {
                L.w { "[Call] handleCallMessage, checkCall fail:${response.reason}" }
                return@launch
            }

            L.i { "[Call] handleCallMessage, checkCall success" }

            handleCallData(roomId, callInfo, envelope, response.data, message.senderId, content, fromServerPush)
        } catch (error: CancellationException) {
            
            throw error
        } catch (error: Exception) {
            L.e { "[Call] handleCallMessage, checkCall fail:${error.stackTraceToString()}" }
        } finally {
            held?.close()
        }
    }
}

internal suspend fun CallMessageHandler.resolveCallInfo(
    calling: SignalServiceProtos.CallMessage.Calling,
    callerId: String,
): CallInfo {
    var callType: CallType
    var conversationId: String? = null
    var callName = calling.roomName ?: ""

    when {
        calling.hasConversationId() && calling.conversationId.hasNumber() -> {
            
            callType = CallType.ONE_ON_ONE
            conversationId = calling.conversationId.number
            contactorCacheManager.getDisplayName(callerId)?.takeIf { it.isNotEmpty() }?.let {
                callName = it
            }
        }
        calling.hasConversationId() && calling.conversationId.hasGroupId() -> {
            
            conversationId = calling.conversationId.groupId.toStringUtf8()
            val inGroup = checkUserIsInGroup(mySelfId, conversationId)
            if (inGroup) {
                callType = CallType.GROUP
                getGroupNameSafely(conversationId)?.takeIf { it.isNotEmpty() }?.let {
                    callName = it
                }
            } else {
                
                callType = CallType.INSTANT
                conversationId = null
                callName = getInstantCallName(callerId)
            }
        }
        else -> {
            
            callType = CallType.INSTANT
            callName = getInstantCallName(callerId)
        }
    }

    return CallInfo(callType, conversationId, callName)
}

internal suspend fun CallMessageHandler.getInstantCallName(callerId: String): String {
    val displayName = contactorCacheManager.getDisplayName(callerId)
    return if (!displayName.isNullOrEmpty()) {
        "${displayName}${ApplicationHelper.instance.getString(R.string.call_instant_call_title)}"
    } else {
        ApplicationHelper.instance.getString(R.string.call_instant_call_title_default)
    }
}

internal suspend fun CallMessageHandler.handleCallData(
    roomId: String,
    callInfo: CallInfo,
    envelope: SignalServiceProtos.Envelope,
    checkCallData: RoomState?,
    senderId: String,
    content: SignalServiceProtos.CallMessage,
    fromServerPush: Boolean,
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
                showIncomingNotificationOrActivity(callData, fromServerPush)
            }
        }
    } else if (content.calling.controlType == CallActionType.INVITE.type) {
        showIncomingNotificationOrActivity(existingCall, fromServerPush)
    }
}

internal fun CallMessageHandler.createCallData(
    roomId: String,
    callInfo: CallInfo,
    envelope: SignalServiceProtos.Envelope,
    callerId: String,
    createdAt: Long?,
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
        source = CallDataSourceType.MESSAGE,
    )
}
