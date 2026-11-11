package com.difft.android.call.handler

import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.data.CallEndType
import com.difft.android.call.data.CallExitParams
import com.difft.android.call.data.CallStatus
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.session.CallExitContext
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.state.OngoingCallPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch


internal interface CallExitSession {
    val callStatus: StateFlow<CallStatus>
    fun getCurrentCallUidList(): List<String>

    
    fun sendEndCall(onComplete: (Boolean) -> Unit)
}





internal class CallExitHandler(
    private val session: CallExitSession,
    private val callToChatController: LCallToChatController,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val publisher: OngoingCallPublisher,
    private val callDataManager: CallDataManager,
    private val callIntent: CallIntent,
    private val callRole: CallRole,
    val context: CallExitContext,
    private val onEndCall: () -> Unit
) {
    private val conversationId: String? get() = context.conversationId
    private val callType: String get() = context.callType

    



    private val isInitiator: Boolean
        get() = callIntent.action == CallIntent.Action.START_CALL

    




    fun handleExit(
        params: CallExitParams,
        callEndType: CallEndType = CallEndType.LEAVE
    ) {
        L.i { "[Call] CallExitHandler handleExit roomId:${params.roomId}, callEndType:$callEndType" }

        
        
        if (isInitiator && session.callStatus.value == CallStatus.CALLING) {
            L.i { "[Call] CallExitHandler: initiator exits before signaling connected, sending end signal" }
            handleInitiatorPreConnectExit(params)
            return
        }

        val callInfo = callDataManager.getCallListData()[params.roomId]

        if (callInfo == null || session.callStatus.value == CallStatus.RECONNECTING) {
            L.i { "[Call] CallExitHandler: Call info is null or reconnecting, ending call directly" }
            onEndCall()
            return
        }

        if (callInfo.type != CallType.ONE_ON_ONE.type && callEndType == CallEndType.LEAVE) {
            L.i { "[Call] CallExitHandler: Non-1v1 call with LEAVE type, ending call directly" }
            onEndCall()
            return
        }

        
        val effectiveType = callInfo.type.orEmpty().ifEmpty { params.callType }.ifEmpty { callType }

        handleExitByStatus(params, effectiveType)
    }

    private fun handleExitByStatus(params: CallExitParams, effectiveType: String) {
        when (val status = session.callStatus.value) {
            CallStatus.CALLING -> {
                handleCallingExit()
            }

            CallStatus.JOINING -> {
                L.i { "[Call] CallExitHandler: Status is JOINING, ending call directly" }
                onEndCall()
            }

            CallStatus.CONNECTED, CallStatus.RECONNECTED -> {
                handleConnectedExit(params, effectiveType)
            }

            else -> {
                L.i { "[Call] CallExitHandler: Status is $status, ending call directly" }
                onEndCall()
            }
        }
    }

    private fun handleCallingExit() {
        L.d { "[Call] CallExitHandler sendCancelCallMessage" }
        onGoingCallStateManager.getCurrentRoomId()?.let { roomId ->
            sendThenEnd("cancel") {
                callToChatController.cancelCall(
                    callerId = callIntent.callerId,
                    callRole = callRole,
                    type = callType,
                    roomId = roomId,
                    conversationId = conversationId
                )
                L.i { "[Call] CallExitHandler: Cancel call message sent, ending call" }
            }
        } ?: run {
            L.w { "[Call] CallExitHandler: No roomId available, ending call directly" }
            onEndCall()
        }
    }

    




    private fun handleInitiatorPreConnectExit(params: CallExitParams) {
        
        
        
        publisher.publish { markInitiatorPreConnectCancelled() }
        val roomId = onGoingCallStateManager.getCurrentRoomId()?.takeIf { it.isNotEmpty() }
            ?: params.roomId
            ?: ""
        val resolvedType = CallType.fromString(callType) ?: CallType.ONE_ON_ONE
        L.i { "[Call] CallExitHandler handleInitiatorPreConnectExit type:$resolvedType roomId:$roomId" }
        sendThenEnd("preConnectEnd") {
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
                    callUidList = session.getCurrentCallUidList(),
                )
            }
            L.i { "[Call] CallExitHandler: pre-connect end signal sent, ending call" }
        }
    }

    private fun handleConnectedExit(params: CallExitParams, effectiveType: String) {
        val roomId = params.roomId
        if (roomId.isNullOrEmpty()) {
            L.w { "[Call] CallExitHandler: RoomId is null or empty, ending call directly" }
            onEndCall()
            return
        }

        callDataManager.removeCallData(roomId)
        L.i { "[Call] CallExitHandler send hangUpCall CallMessage roomId:$roomId" }

        
        val callUidList = session.getCurrentCallUidList()

        session.sendEndCall(onComplete = {
            
            
            if (effectiveType != CallType.INSTANT.type && conversationId.isNullOrEmpty()) {
                L.w { "[Call] CallExitHandler: conversationId is missing for callType:$effectiveType, ending call directly" }
                onEndCall()
                return@sendEndCall
            }

            sendThenEnd("hangup") {
                callToChatController.hangUpCall(
                    callerId = params.callerId,
                    callRole = callRole,
                    type = effectiveType,
                    roomId = roomId,
                    conversationId = conversationId,
                    callUidList = callUidList,
                )
                L.i { "[Call] CallExitHandler: Hang up call message sent, ending call" }
            }
        })
    }

    
    private fun sendThenEnd(tag: String, send: suspend () -> Unit) {
        appScope.launch {
            try {
                send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e(e) { "[Call] CallExitHandler: $tag signaling failed, ending call" }
            } finally {
                onEndCall()
            }
        }
    }
}
