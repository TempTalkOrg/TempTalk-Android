package com.difft.android.call.session

import android.content.Intent
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
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallEngine
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallToChatController
import com.difft.android.call.R
import com.difft.android.call.connect.CallConnectionCoordinator
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.exception.StartCallException
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.CallRingtoneManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.state.OngoingCallPublisher
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import difft.android.messageserialization.For
import io.livekit.android.room.Room
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import livekit.LivekitTemptalk






internal class CallSessionStarter(
    private val scope: CoroutineScope,
    private val room: Room,
    private val roomCtl: CallRoomController,
    private val connectionCoordinator: CallConnectionCoordinator,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val publisher: OngoingCallPublisher,
    private val callRingtoneManager: CallRingtoneManager,
    private val callDataManager: CallDataManager,
    private val contactorCacheManager: ContactorCacheManager,
    private val callToChatController: LCallToChatController,
    private val messageEncryptor: INewMessageContentEncryptor,
    private val callIntent: CallIntent,
    private val e2eeEnable: Boolean,
    private val mySelfId: String,
    private val createCallMsgConfig: Boolean,
    private val onRoomIdAssigned: (String) -> Unit,
    private val onE2eeKeyAssigned: (ByteArray) -> Unit,
    
    
    private val isSessionEnding: () -> Boolean,
    private val startCallResponses: () -> Flow<LivekitTemptalk.TTCallResponse?> = { room::ttCallResp.flow },
    private val callerRingback: CallerRingback = CallerRingback(callRingtoneManager),
) {
    private val responseGate = Mutex()
    private var connectionFlowAborted = false

    








    fun start() {
        scope.launch(Dispatchers.IO) {
            val response = async { startCallResponses().filterNotNull().first() }
            val responseHandler = launch {
                val firstResponse = response.await()
                responseGate.lock()
                try {
                    if (connectionFlowAborted) {
                        L.w { "[Call] CallSessionStarter late start call response skipped: connection flow aborted" }
                        return@launch
                    }
                    handleStartCallResponse(firstResponse)
                } finally {
                    responseGate.unlock()
                }
            }
            val connected = connectionCoordinator.connectToRoomWithFailover(
                callParams = callIntent.startCallParams,
                useQuic = LCallEngine.isUseQuicSignal(),
            )
            if (!connected) {
                responseGate.withLock { connectionFlowAborted = true }
                response.cancel()
                responseHandler.cancel()
                L.e { "[Call] CallSessionStarter connectionFlow aborted: connectToRoom failed" }
                return@launch
            }
            if (withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { response.await() } == null) {
                response.cancel()
                handleStartCallResponse(null)
            }
        }
    }

    
    internal suspend fun handleStartCallResponse(response: LivekitTemptalk.TTCallResponse?) {
        L.i { "[Call] CallSessionStarter start call response callback." }
        if (response == null) {
            if (isSessionEnding()) {
                L.w { "[Call] CallSessionStarter null start call response skipped: session ending" }
                return
            }
            L.e { "[Call] CallSessionStarter start call response is null." }
            roomCtl.collectError(StartCallException(getString(R.string.call_server_connect_exception_error)))
            return
        }
        if (response.body == null || response.base.status != 0) {
            if (isSessionEnding()) {
                L.w { "[Call] CallSessionStarter invalid start call response skipped: session ending" }
                return
            }
            L.e { "[Call] CallSessionStarter start call response error, status:${response.base.status} reason:${response.base.reason}." }
            when (response.base.status) {
                22001 -> roomCtl.collectError(StartCallException(getString(R.string.call_connect_error_ended)))
                else -> roomCtl.collectError(StartCallException(response.base.reason))
            }
            return
        }

        val roomId = response.body.roomId
        val callerId = callIntent.callerId
        val callType = CallType.fromString(callIntent.callType) ?: CallType.ONE_ON_ONE

        
        
        
        if (
            callIntent.action == CallIntent.Action.START_CALL &&
            onGoingCallStateManager.isInitiatorPreConnectCancelled()
        ) {
            reCancelAfterInitiatorExit(callType, roomId.orEmpty(), callerId)
            return
        }
        if (isSessionEnding()) {
            L.w { "[Call] CallSessionStarter start call response skipped: session ending roomId:$roomId" }
            return
        }

        if (roomId != null) {
            onRoomIdAssigned(roomId)
        }
        publisher.publish { setCurrentRoomId(roomId) }

        val forWhat = when (callType) {
            CallType.GROUP -> For.Group(callIntent.conversationId!!)
            CallType.ONE_ON_ONE -> For.Account(callIntent.conversationId!!)
            else -> For.Account(callIntent.callerId)
        }

        if (e2eeEnable) {
            val mk = messageEncryptor.decryptCallKey(response.body.publicKey, response.body.emk)
            if (mk == null) {
                roomCtl.collectError(StartCallException(getString(R.string.call_e2ee_key_error)))
                return
            }
            onE2eeKeyAssigned(mk)
        }

        when (callIntent.action) {
            CallIntent.Action.START_CALL -> onStartCallSucceeded(
                forWhat = forWhat,
                callerId = callerId,
                callType = callType,
                roomId = response.body.roomId,
                createdAt = response.body.createdAt,
                systemShowTimestamp = response.body.systemShowTimestamp,
            )
            CallIntent.Action.JOIN_CALL -> {
                response.body.roomId?.let { rid ->
                    sendJoinSyncControlMessage(forWhat, rid, callerId)
                    callToChatController.cancelNotificationById(rid.hashCode())
                }
            }
            else -> Unit
        }
    }

    










    internal suspend fun onStartCallSucceeded(
        forWhat: For,
        callerId: String,
        callType: CallType,
        roomId: String?,
        createdAt: Long,
        systemShowTimestamp: Long,
    ): Boolean {
        
        if (onGoingCallStateManager.isInitiatorPreConnectCancelled()) {
            reCancelAfterInitiatorExit(callType, roomId ?: "", callerId)
            return false
        }
        if (isSessionEnding()) {
            L.w { "[Call] CallSessionStarter start response skipped: session ending roomId:$roomId" }
            return false
        }
        startCallRingTone(callType)
        roomId?.let { rid ->
            addStartCallData(forWhat, callerId, callIntent.roomName, rid, callType, createdAt)
        }
        sendStartCallTextMessage(forWhat, callType, systemShowTimestamp)
        return true
    }

    






    private suspend fun reCancelAfterInitiatorExit(callType: CallType, roomId: String, callerId: String) {
        L.w { "[Call] CallSessionStarter start response arrived after initiator exit -> re-cancel roomId:$roomId type:$callType" }
        val conversationId = callIntent.conversationId
        if (callType == CallType.GROUP) {
            callToChatController.hangUpCall(
                callerId = callerId,
                callRole = CallRole.CALLER,
                type = callIntent.callType,
                roomId = roomId,
                conversationId = conversationId,
                callUidList = emptyList(),
            )
        } else {
            callToChatController.cancelCall(
                callerId = callerId,
                callRole = CallRole.CALLER,
                type = callIntent.callType,
                roomId = roomId,
                conversationId = conversationId,
            )
        }
    }

    private fun startCallRingTone(callType: CallType) {
        scope.launch(Dispatchers.IO) {
            val ringIntent = Intent().apply {
                putExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE, callType.type)
                putExtra(LCallConstants.BUNDLE_KEY_CALL_ROLE, CallRole.CALLER.type)
            }
            
            
            callerRingback.start { callRingtoneManager.startRingTone(ringIntent) }
        }
    }

    private fun addStartCallData(
        forWhat: For,
        callerId: String,
        callName: String,
        roomId: String,
        callType: CallType,
        createdAt: Long,
    ) {
        val callData = CallData(
            
            
            
            
            
            
            type = roomCtl.callType.value.ifEmpty { callType.type },
            version = 0,
            createdAt = createdAt,
            roomId = roomId,
            caller = CallDataCaller(callerId, DEFAULT_DEVICE_ID),
            conversation = forWhat.id,
            null,
            callName = callName,
            source = CallDataSourceType.LOCAL,
        )
        callDataManager.addCallData(callData)
    }

    private fun sendStartCallTextMessage(forWhat: For, callType: CallType, systemShowTimestamp: Long) {
        scope.launch(Dispatchers.IO) {
            val createCallMessageTime = System.currentTimeMillis()
            val mySelfName = contactorCacheManager.getDisplayName(mySelfId)
            val textContent = if (callType == CallType.GROUP) {
                ApplicationHelper.instance.getString(R.string.call_group_send_message, mySelfName)
            } else {
                ApplicationHelper.instance.getString(R.string.call_1v1_send_message)
            }
            LCallManager.sendOrLocalCallTextMessage(
                CallActionType.START,
                textContent,
                DEFAULT_DEVICE_ID,
                createCallMessageTime,
                systemShowTimestamp,
                For.Account(mySelfId),
                forWhat,
                callType,
                createCallMsgConfig,
            )
        }
    }

    private fun sendJoinSyncControlMessage(forWhat: For, roomId: String, callerId: String) {
        scope.launch(Dispatchers.IO) {
            callToChatController.syncJoinedMessage(
                mySelfId,
                CallRole.CALLEE,
                callerId,
                CallRole.CALLEE.type,
                roomId,
                forWhat.id,
                null,
            )
        }
    }

    private companion object {
        const val RESPONSE_TIMEOUT_MS = 15_000L
    }
}
