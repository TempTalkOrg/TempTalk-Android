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
import com.difft.android.websocket.api.util.INewMessageContentEncryptor
import difft.android.messageserialization.For
import io.livekit.android.room.Room
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the call session startup sequence after the ViewModel is constructed:
 *  1. connect the room with failover,
 *  2. await the server's `ttCallResp` and validate it,
 *  3. extract the room id and decrypt the E2EE key,
 *  4. for CALLER: start ringtone, persist local `CallData`, send start-call text,
 *  5. for CALLEE: send the join-sync control message and cancel incoming-call notification.
 *
 * Extracted from `LCallViewModel.launchConnectionFlow` /
 * `processStartCallResponse` / `startCallRingTone` / `addStartCallData` /
 * `sendStartCallTextMessage` / `sendJoinSyncControlMessage` to keep the
 * ViewModel within the 500-line project limit.
 */
internal class CallSessionStarter(
    private val scope: CoroutineScope,
    private val room: Room,
    private val roomCtl: CallRoomController,
    private val connectionCoordinator: CallConnectionCoordinator,
    private val onGoingCallStateManager: OnGoingCallStateManager,
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
) {

    /** Launch the end-to-end startup flow on an IO dispatcher. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            val connected = connectionCoordinator.connectToRoomWithFailover(
                callParams = callIntent.startCallParams,
                useQuic = LCallEngine.isUseQuicSignal(),
            )
            if (!connected) {
                L.e { "[Call] CallSessionStarter connectionFlow aborted: connectToRoom failed" }
                return@launch
            }
            processStartCallResponse()
        }
    }

    private suspend fun processStartCallResponse() {
        val response = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
            room::ttCallResp.flow.filterNotNull().first()
        }
        L.i { "[Call] CallSessionStarter start call response callback." }
        if (response == null) {
            L.e { "[Call] CallSessionStarter start call response is null." }
            roomCtl.collectError(StartCallException(getString(R.string.call_server_connect_exception_error)))
            return
        }
        if (response.body == null || response.base.status != 0) {
            L.e { "[Call] CallSessionStarter start call response error, status:${response.base.status} reason:${response.base.reason}." }
            when (response.base.status) {
                22001 -> roomCtl.collectError(StartCallException(getString(R.string.call_connect_error_ended)))
                else -> roomCtl.collectError(StartCallException(response.base.reason))
            }
            return
        }

        val roomId = response.body.roomId
        if (roomId != null) {
            onRoomIdAssigned(roomId)
        }
        onGoingCallStateManager.setCurrentRoomId(roomId)

        val callerId = callIntent.callerId
        val callType = CallType.fromString(callIntent.callType) ?: CallType.ONE_ON_ONE
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
            CallIntent.Action.START_CALL -> {
                startCallRingTone(callType)
                response.body.roomId?.let { rid ->
                    addStartCallData(forWhat, callerId, callIntent.roomName, rid, callType, response.body.createdAt)
                }
                sendStartCallTextMessage(forWhat, callType, response.body.systemShowTimestamp)
            }
            CallIntent.Action.JOIN_CALL -> {
                response.body.roomId?.let { rid ->
                    sendJoinSyncControlMessage(forWhat, rid, callerId)
                    callToChatController.cancelNotificationById(rid.hashCode())
                }
            }
            else -> Unit
        }
    }

    private fun startCallRingTone(callType: CallType) {
        scope.launch(Dispatchers.IO) {
            val ringIntent = Intent().apply {
                putExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE, callType.type)
                putExtra(LCallConstants.BUNDLE_KEY_CALL_ROLE, CallRole.CALLER.type)
            }
            callRingtoneManager.startRingTone(ringIntent)
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
            type = callType.type,
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
