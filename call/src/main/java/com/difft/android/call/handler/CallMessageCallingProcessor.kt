package com.difft.android.call.handler

import com.difft.android.base.utils.globalServices

import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallDataCaller
import com.difft.android.base.call.CallDataSourceType
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
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

/**
 * Parsed call information used when processing `Calling` messages.
 *
 * Kept as a package-private top-level type so both the calling processor and
 * the text-message helper can share it without introducing nested visibility
 * noise.
 */
internal data class CallInfo(
    val callType: CallType,
    val conversationId: String?,
    val callName: String,
)

/**
 * `Calling` message processing extensions for [CallMessageHandler].
 *
 * Splits the `START` / `INVITE` pipeline away from the main handler so it
 * stays within the 500-line budget. All logic stays functionally identical
 * to the pre-refactor implementation.
 */
internal fun CallMessageHandler.handleCallingMessage(
    message: SignalServiceDataClass,
    envelope: SignalServiceProtos.Envelope,
    content: SignalServiceProtos.CallMessage,
    roomId: String,
) {
    L.i { "[Call] handleCallMessage, has calling message envelope.timestamp:${envelope.timestamp}" }
    L.i { "[Call] handleCallMessage, start checkCall roomId: $roomId" }

    appScope.launch(Dispatchers.IO) {
        try {
            val callerId = content.calling?.caller ?: message.senderId
            if (envelope.source != callerId) {
                L.e { "[Call] handleCallMessage, source is not callerId" }
                return@launch
            }

            val callInfo = resolveCallInfo(content.calling, callerId)

            // Persist the chat-side call record BEFORE checkCall — independent of whether the call
            // is still live. A call started while this device was offline may already have ended by
            // the time the backlog is drained (checkCall then returns fail/userStopped); we still
            // want the "X called you / started a call" trace + unread red dot. checkCall below only
            // gates the LIVE-call handling (active-call list + incoming ring UI).
            if (content.calling.createCallMsg) {
                handleCallTextMessage(envelope, content, callInfo)
            }

            val response = callService.checkCall((globalServices.userManager.getUserData()?.microToken ?: ""), roomId)
            if (response.status != CallMessageHandler.RESPONSE_STATUS_SUCCESS ||
                response.data?.userStopped == true
            ) {
                L.e { "[Call] handleCallMessage, checkCall fail:${response.reason}" }
                return@launch
            }

            L.i { "[Call] handleCallMessage, checkCall success" }

            handleCallData(roomId, callInfo, envelope, response.data, message.senderId, content)
        } catch (error: CancellationException) {
            // Preserve structured concurrency — never swallow coroutine cancellation.
            throw error
        } catch (error: Exception) {
            L.e { "[Call] handleCallMessage, checkCall fail:${error.stackTraceToString()}" }
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
            // 1:1 call
            callType = CallType.ONE_ON_ONE
            conversationId = calling.conversationId.number
            contactorCacheManager.getDisplayName(callerId)?.takeIf { it.isNotEmpty() }?.let {
                callName = it
            }
        }
        calling.hasConversationId() && calling.conversationId.hasGroupId() -> {
            // Group call
            conversationId = calling.conversationId.groupId.toStringUtf8()
            val inGroup = checkUserIsInGroup(mySelfId, conversationId)
            if (inGroup) {
                callType = CallType.GROUP
                getGroupNameSafely(conversationId)?.takeIf { it.isNotEmpty() }?.let {
                    callName = it
                }
            } else {
                // Not in the group anymore — fall back to instant call.
                callType = CallType.INSTANT
                conversationId = null
                callName = getInstantCallName(callerId)
            }
        }
        else -> {
            // Instant call
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
