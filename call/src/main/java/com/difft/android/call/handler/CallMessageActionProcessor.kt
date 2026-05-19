package com.difft.android.call.handler

import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.call.LCallManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * Action-message processing extensions for [CallMessageHandler].
 *
 * Handles the `Joined` / `Cancel` / `Reject` / `Hangup` flows plus their
 * small cleanup helpers. Logic is unchanged from the pre-refactor
 * implementation.
 */
internal fun CallMessageHandler.handleJoinedMessage(
    content: SignalServiceProtos.CallMessage,
    roomId: String,
) {
    L.i { "[Call] handleCallMessage, hasJoined Message:${content.joined.roomId}" }

    cancelNotificationAndHandleService(roomId, CallActionType.JOINED, "joined: other device joined the call")

    callDataManager.getCallListData().let { callingData ->
        callingData[roomId]?.hasAnotherDeviceJoined = true
        callDataManager.updateCallingListData(callingData)
    }

    // Clear the conversation-list critical-alert highlight asynchronously.
    appScope.launch(Dispatchers.IO) {
        dismissCriticalAlertForRoom(roomId)
    }
}

internal fun CallMessageHandler.handleCancelMessage(
    content: SignalServiceProtos.CallMessage,
    roomId: String,
) {
    L.i { "[Call] handleCallMessage, hasCancel Message:${content.cancel.roomId}" }

    cancelNotificationAndHandleService(roomId, CallActionType.CANCEL, "cancel: caller cancel the call")
}

internal fun CallMessageHandler.handleRejectMessage(
    message: SignalServiceDataClass,
    content: SignalServiceProtos.CallMessage,
    roomId: String,
) {
    L.i { "[Call] handleCallMessage, hasRejected Message:${content.reject.roomId}" }

    // If another device of the current user rejected while this device is
    // already in the call, ignore that self-reject.
    if (shouldIgnoreSelfReject(message, roomId)) {
        L.i { "[Call] handleCallMessage reject, remote myself device reject, but local has in meeting." }
        return
    }

    // Cancel the incoming-call notification first.
    callToChatController.cancelNotificationById(roomId.hashCode())

    // Clean cached call data; keep group-call entries intact.
    removeCallDataIfOneOnOne(roomId)

    // Case A: this device is the caller and a remote callee rejected the call.
    if (onGoingCallStateManager.isInCalling() &&
        onGoingCallStateManager.getCurrentRoomId() == roomId
    ) {
        updateControlMessage(CallActionType.REJECT, roomId)
    }

    // Case B: this device is a callee and another device of the current user
    // rejected the call.
    cancelNotificationAndHandleService(roomId, CallActionType.REJECT, "reject: your other device reject the call")
}

internal fun CallMessageHandler.handleHangupMessage(
    content: SignalServiceProtos.CallMessage,
    roomId: String,
) {
    L.i { "[Call] handleCallMessage, hasHangup Message:${content.hangup.roomId}" }

    if (onGoingCallStateManager.isInCalling() &&
        onGoingCallStateManager.getCurrentRoomId() == roomId
    ) {
        val controlMessage = OnGoingCallStateManager.ControlMessage(
            actionType = CallActionType.HANGUP,
            roomId = roomId,
        )
        onGoingCallStateManager.updateControlMessage(controlMessage)
    }
    callDataManager.removeCallData(roomId)
}

/**
 * Clear the critical-alert highlight on the conversation list for [roomId].
 */
internal suspend fun CallMessageHandler.dismissCriticalAlertForRoom(roomId: String) {
    val data = callDataManager.getCallData(roomId)
    if (data == null) {
        L.w { "[Call] handleCallMessage joined: call data is null for roomId=$roomId, cannot dismiss critical alert" }
        return
    }
    when (data.type) {
        CallType.INSTANT.type -> {
            data.caller.uid?.let { uid -> clearCriticalAlertFor(uid) }
        }
        CallType.ONE_ON_ONE.type -> {
            data.conversation?.let { conversationId -> clearCriticalAlertFor(conversationId) }
        }
        CallType.GROUP.type -> {
            data.conversation?.let { conversationId ->
                val isInGroup = checkUserIsInGroup(mySelfId, conversationId)
                if (isInGroup) {
                    clearCriticalAlertFor(conversationId)
                } else {
                    data.caller.uid?.let { uid -> clearCriticalAlertFor(uid) }
                }
            }
        }
    }
}

private fun CallMessageHandler.clearCriticalAlertFor(id: String) {
    dbRoomStore.clearCriticalAlert(id)
    callToChatController.cancelCriticalAlertNotification(id)
    LCallManager.dismissCriticalAlert(id)
}

/**
 * Decide whether an incoming `reject` coming from the current user's other
 * device should be ignored because the local device is already in the call.
 */
internal fun CallMessageHandler.shouldIgnoreSelfReject(
    message: SignalServiceDataClass,
    roomId: String,
): Boolean {
    return message.senderId == mySelfId &&
            onGoingCallStateManager.isInCalling() &&
            onGoingCallStateManager.getCurrentRoomId() == roomId
}

/**
 * Remove cached call data only when it represents a one-on-one call; group
 * calls keep their cached entry so further control messages can still be
 * matched.
 */
internal fun CallMessageHandler.removeCallDataIfOneOnOne(roomId: String) {
    val callDataList = callDataManager.getCallListData()
    if (callDataList.containsKey(roomId)) {
        callDataList[roomId]?.let {
            if (it.type == CallType.ONE_ON_ONE.type) {
                callDataManager.removeCallData(roomId)
            }
        }
    }
}
