package com.difft.android.call.handler

import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.call.R
import difft.android.messageserialization.For
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * Text-message generation extensions for [CallMessageHandler].
 *
 * Builds and persists the chat-side text message (`JOINED` marker) that
 * mirrors an inbound `START` / `INVITE` `Calling` message. Logic is
 * unchanged from the pre-refactor implementation.
 */
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

internal fun CallMessageHandler.handleStartCallTextMessage(
    envelope: SignalServiceProtos.Envelope,
    content: SignalServiceProtos.CallMessage,
    callInfo: CallInfo,
    textContent: String,
    callerId: String,
) {
    val isSelfSync = envelope.source == mySelfId && envelope.sourceDevice != DEFAULT_DEVICE_ID

    if (isSelfSync) {
        // Sync message from another device of the current user.
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
        // Incoming START from the remote peer.
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

internal fun CallMessageHandler.handleInviteCallTextMessage(
    envelope: SignalServiceProtos.Envelope,
    content: SignalServiceProtos.CallMessage,
    callInfo: CallInfo,
    textContent: String,
    callerId: String,
) {
    val isSelfSync = envelope.source == mySelfId && envelope.sourceDevice != DEFAULT_DEVICE_ID
    val inviteeList = content.calling.calleesList ?: return

    if (isSelfSync) {
        // Sync INVITE from another device of the current user.
        inviteeList.forEachIndexed { index, invitee ->
            val callMessageTime = content.calling.timestamp + index
            callToChatController.sendOrCreateCallTextMessage(
                CallActionType.JOINED, textContent, envelope.sourceDevice,
                callMessageTime, envelope.systemShowTimestamp,
                For.Account(callerId), For.Account(invitee), callInfo.callType, true
            )
        }
    } else {
        // Incoming INVITE addressed to the current user.
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
