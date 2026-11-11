package com.difft.android.call.handler

import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.utils.appScope
import com.difft.android.call.LCallManager
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.whispersystems.signalservice.internal.push.SignalServiceProtos


internal fun CallMessageHandler.handleJoinedMessage(
    content: SignalServiceProtos.CallMessage,
    roomId: String,
    admission: MessagingTicket,
) {
    L.i { "[Call] handleCallMessage, hasJoined Message:${content.joined.roomId}" }

    cancelNotificationAndHandleService(roomId, CallActionType.JOINED, "joined: other device joined the call")

    callDataManager.getCallListData().let { callingData ->
        callingData[roomId]?.hasAnotherDeviceJoined = true
        callDataManager.updateCallingListData(callingData)
    }

    
    
    val held = admission.retain("call-joined")
    appScope.launch(Dispatchers.IO) {
        try {
            dismissCriticalAlertForRoom(roomId)
        } finally {
            held?.close()
        }
    }
}

internal fun CallMessageHandler.handleCancelMessage(
    content: SignalServiceProtos.CallMessage,
    roomId: String,
) {
    L.i { "[Call] handleCallMessage, hasCancel Message:${content.cancel.roomId}" }

    
    criticalAlertManager.dismissForRoom(roomId)

    cancelNotificationAndHandleService(roomId, CallActionType.CANCEL, "cancel: caller cancel the call")
}

internal fun CallMessageHandler.handleRejectMessage(
    message: SignalServiceDataClass,
    content: SignalServiceProtos.CallMessage,
    roomId: String,
) {
    L.i { "[Call] handleCallMessage, hasRejected Message:${content.reject.roomId}" }

    
    
    if (shouldIgnoreSelfReject(message, roomId)) {
        L.i { "[Call] handleCallMessage reject, remote myself device reject, but local has in meeting." }
        return
    }

    
    callToChatController.cancelNotificationById(roomId.hashCode())

    
    removeCallDataIfOneOnOne(roomId)

    
    if (onGoingCallStateManager.isInCalling() &&
        onGoingCallStateManager.getCurrentRoomId() == roomId
    ) {
        endOngoingCall(CallActionType.REJECT, roomId)
    }

    
    
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
        endOngoingCall(CallActionType.HANGUP, roomId)
    }

    
    
    
    
    cancelNotificationAndHandleService(roomId, CallActionType.HANGUP, "hangup: caller hangup the call")

    callDataManager.removeCallData(roomId)
}




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





internal fun CallMessageHandler.shouldIgnoreSelfReject(
    message: SignalServiceDataClass,
    roomId: String,
): Boolean {
    return message.senderId == mySelfId &&
            onGoingCallStateManager.isInCalling() &&
            onGoingCallStateManager.getCurrentRoomId() == roomId
}






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
