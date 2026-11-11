package com.difft.android.call.handler

import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.call.R
import difft.android.messageserialization.For
import org.whispersystems.signalservice.internal.push.SignalServiceProtos


internal suspend fun CallMessageHandler.handleCallTextMessage(
    envelope: SignalServiceProtos.Envelope,
    content: SignalServiceProtos.CallMessage,
    callInfo: CallInfo,
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

internal suspend fun CallMessageHandler.generateCallTextContent(
    calling: SignalServiceProtos.CallMessage.Calling,
    callType: CallType,
    callerId: String,
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

internal suspend fun CallMessageHandler.handleStartCallTextMessage(
    envelope: SignalServiceProtos.Envelope,
    content: SignalServiceProtos.CallMessage,
    callInfo: CallInfo,
    textContent: String,
    callerId: String,
) {
    val isSelfSync = envelope.source == mySelfId && envelope.sourceDevice != DEFAULT_DEVICE_ID

    if (isSelfSync) {
        
        callInfo.conversationId?.let { otherSideId ->
            val forWhat = when (callInfo.callType) {
                CallType.GROUP -> For.Group(otherSideId)
                else -> For.Account(otherSideId)
            }
            callToChatController.saveReceivedCallTextMessage(
                CallActionType.JOINED, textContent, envelope.sourceDevice,
                content.calling.timestamp, envelope.systemShowTimestamp,
                For.Account(callerId), forWhat,
            )
        } ?: run {
            L.e { "[Call] handleCallMessage conversationId is null" }
        }
    } else {
        
        val forWhat = when (callInfo.callType) {
            CallType.GROUP -> callInfo.conversationId?.let { For.Group(it) }
            else -> For.Account(callerId)
        }
        forWhat?.let {
            callToChatController.saveReceivedCallTextMessage(
                CallActionType.JOINED, textContent, envelope.sourceDevice,
                content.calling.timestamp, envelope.systemShowTimestamp,
                For.Account(callerId), it,
            )
        } ?: run {
            L.e { "[Call] handleCallMessage forWhat is null" }
        }
    }
}

internal suspend fun CallMessageHandler.handleInviteCallTextMessage(
    envelope: SignalServiceProtos.Envelope,
    content: SignalServiceProtos.CallMessage,
    callInfo: CallInfo,
    textContent: String,
    callerId: String,
) {
    val isSelfSync = envelope.source == mySelfId && envelope.sourceDevice != DEFAULT_DEVICE_ID
    val inviteeList = content.calling.calleesList ?: return

    if (isSelfSync) {
        
        inviteeList.forEachIndexed { index, invitee ->
            val callMessageTime = content.calling.timestamp + index
            callToChatController.saveReceivedCallTextMessage(
                CallActionType.JOINED, textContent, envelope.sourceDevice,
                callMessageTime, envelope.systemShowTimestamp,
                For.Account(callerId), For.Account(invitee),
            )
        }
    } else {
        
        val index = inviteeList.indexOf(mySelfId)
        if (index >= 0) {
            val callMessageTime = content.calling.timestamp + index
            callToChatController.saveReceivedCallTextMessage(
                CallActionType.JOINED, textContent, envelope.sourceDevice,
                callMessageTime, envelope.systemShowTimestamp,
                For.Account(callerId), For.Account(callerId),
            )
        }
    }
}
