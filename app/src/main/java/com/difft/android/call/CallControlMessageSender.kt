package com.difft.android.call

import com.difft.android.base.utils.globalServices

import com.difft.android.base.call.CallActionType
import com.difft.android.base.call.CallEncryptOutcome
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.call.ControlMessageRequestBody
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.websocket.api.messages.DetailMessageType
import com.difft.android.websocket.api.util.CallMessageCreator
import difft.android.messageserialization.For
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles outbound call control messages (reject, cancel, hangup, joined).
 *
 * All four operations share the same pattern:
 * resolve target → encrypt → build [ControlMessageRequestBody] → POST via [LCallHttpService].
 */
internal class CallControlMessageSender(
    private val callService: LCallHttpService,
    private val callMessageCreator: CallMessageCreator,
    private val mySelfId: String,
    private val onGoingCallStateManager: OnGoingCallStateManager,
) {

    suspend fun rejectCall(
        callerId: String,
        callRole: CallRole?,
        type: String,
        roomId: String,
        conversationId: String?,
    ) {
        val callType = CallType.fromString(type) ?: CallType.ONE_ON_ONE
        val forWhat = if (callType == CallType.GROUP) {
            conversationId?.let { For.Group(it) }
        } else {
            For.Account(callerId)
        }
        sendControlMessage(
            tag = "rejectCall",
            forWhat = forWhat,
            callType = callType,
            callRole = callRole,
            actionType = CallActionType.REJECT,
            conversationId = conversationId,
            roomId = roomId,
            callerId = mySelfId,
            detailType = if (callType == CallType.ONE_ON_ONE) DetailMessageType.CallEnd.value else DetailMessageType.Unknown.value,
        )
    }

    suspend fun cancelCall(
        callerId: String,
        callRole: CallRole?,
        type: String,
        roomId: String,
        conversationId: String?,
    ) {
        val callType = CallType.fromString(type) ?: CallType.ONE_ON_ONE
        val forWhat = if (callType == CallType.GROUP) {
            conversationId?.let { For.Group(it) }
        } else {
            conversationId?.let { For.Account(it) }
        }
        L.d { "[Call] cancelCall, params mySelfId:$mySelfId roomId:$roomId callerId:$callerId type:$type conversationId:$conversationId" }
        sendControlMessage(
            tag = "cancelCall",
            forWhat = forWhat,
            callType = callType,
            callRole = callRole,
            actionType = CallActionType.CANCEL,
            conversationId = conversationId,
            roomId = roomId,
            callerId = mySelfId,
            detailType = if (callType == CallType.ONE_ON_ONE) DetailMessageType.CallEnd.value else DetailMessageType.Unknown.value,
        )
    }

    suspend fun hangUpCall(
        callerId: String,
        callRole: CallRole?,
        type: String,
        roomId: String,
        conversationId: String?,
        callUidList: List<String>,
    ) {
        val callType = CallType.fromString(type) ?: CallType.ONE_ON_ONE
        val forWhat = when (callType) {
            CallType.GROUP -> conversationId?.let { For.Group(it) }
            CallType.ONE_ON_ONE -> if (callRole == CallRole.CALLER) For.Account(conversationId!!) else For.Account(callerId)
            else -> For.Account(callerId)
        }
        L.d { "[Call] hangUpCall, params mySelfId:$mySelfId roomId:$roomId callerId:$callerId type:$type conversationId:$conversationId" }
        sendControlMessage(
            tag = "hangUpCall",
            forWhat = forWhat,
            callType = callType,
            callRole = callRole,
            actionType = CallActionType.HANGUP,
            conversationId = conversationId,
            roomId = roomId,
            callerId = callerId,
            detailType = if (callType == CallType.ONE_ON_ONE) DetailMessageType.CallEnd.value else DetailMessageType.GroupCallEnd.value,
            callUidList = callUidList,
        )
    }

    fun syncJoinedMessage(
        receiverId: String,
        callRole: CallRole?,
        callerId: String,
        type: String,
        roomId: String,
        conversationId: String?,
        mKey: ByteArray?,
    ) {
        val forWhat = For.Account(receiverId)
        val callType = CallType.fromString(type) ?: CallType.ONE_ON_ONE
        appScope.launch {
            try {
                val callEncryptResult = when (val outcome = withContext(Dispatchers.IO) {
                    callMessageCreator.createCallMessage(
                        forWhat, callType, callRole, CallActionType.JOINED,
                        conversationId, null, listOf(roomId), null, callerId, mKey,
                    )
                }) {
                    is CallEncryptOutcome.Success -> outcome.result
                    is CallEncryptOutcome.Failed -> {
                        L.e { "[Call] syncJoinedMessage encryption failed: ${outcome.reason}" }
                        return@launch
                    }
                }
                val body = ControlMessageRequestBody(
                    roomId = roomId,
                    System.currentTimeMillis(),
                    cipherMessages = callEncryptResult.cipherMessages,
                    clientCallId = onGoingCallStateManager.getClientCallId(),
                )
                val result = withContext(Dispatchers.IO) {
                    callService.controlMessages((globalServices.userManager.getUserData()?.microToken ?: ""), body)
                }
                if (result.status == 0) {
                    result.data?.let { data -> L.d { "[Call] syncJoinedMessage, request success, response data:$data" } }
                } else {
                    L.e { "[Call] syncJoinedMessage, response status fail, reason:${result.reason}" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[Call] syncJoinedMessage, request fail, error:${e.message}" }
            }
        }
    }

    private suspend fun sendControlMessage(
        tag: String,
        forWhat: For?,
        callType: CallType,
        callRole: CallRole?,
        actionType: CallActionType,
        conversationId: String?,
        roomId: String,
        callerId: String,
        detailType: Int = DetailMessageType.Unknown.value,
        callUidList: List<String> = emptyList(),
    ) {
        withContext(Dispatchers.IO) {
            try {
                val cipherMessages = when (val outcome = callMessageCreator.createCallMessage(
                    forWhat, callType, callRole, actionType,
                    conversationId, null, listOf(roomId), null, callerId, null, callUidList,
                )) {
                    is CallEncryptOutcome.Success -> outcome.result.cipherMessages
                    is CallEncryptOutcome.Failed -> {
                        require(actionType.isCancel() || actionType.isReject() || actionType.isHangUp()) {
                            "Encryption bypass is only allowed for termination actions, got $actionType"
                        }
                        L.w { "[Call] $tag encryption failed: ${outcome.reason}, sending control message without cipher" }
                        null
                    }
                }
                val body = ControlMessageRequestBody(
                    roomId = roomId,
                    System.currentTimeMillis(),
                    cipherMessages = cipherMessages,
                    detailMessageType = detailType,
                    clientCallId = onGoingCallStateManager.getClientCallId(),
                )
                val result = callService.controlMessages((globalServices.userManager.getUserData()?.microToken ?: ""), body)
                if (result.status == 0) {
                    result.data?.let { data -> L.d { "[Call] $tag, request success, response data:$data" } }
                } else {
                    L.e { "[Call] $tag, response status fail, reason:${result.reason}" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[Call] $tag, request fail, error:${e.stackTraceToString()}" }
            }
        }
    }
}
