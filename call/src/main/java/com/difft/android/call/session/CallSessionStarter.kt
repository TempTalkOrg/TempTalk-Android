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

    /**
     * START_CALL 成功响应后的副作用编排。抽出为独立方法（与 LiveKit [Room] 解耦）以便单元测试。
     *
     * 门闩命中时（主叫已在窗口 W 退出）：**确定性地**跳过响铃 / 建本地 CallData / 发始通话消息，
     * 避免向对端发出与取消矛盾的"始通话"消息——这是本方法对该竞态提供的可靠保证；随后调用
     * [reCancelAfterInitiatorExit] 做 **best-effort** 补发权威取消。远端止铃的最终保证在后端
     * clientCallId 墓碑（见 [reCancelAfterInitiatorExit] 说明），本方法不承担该保证。
     *
     * @return true = 已走正常主叫路径；false = 主叫已退出，跳过副作用并尝试补发取消。
     */
    internal suspend fun onStartCallSucceeded(
        forWhat: For,
        callerId: String,
        callType: CallType,
        roomId: String?,
        createdAt: Long,
        systemShowTimestamp: Long,
    ): Boolean {
        // 主叫已在窗口 W 退出：房间此刻才由服务端建出，不能再响铃/建本地数据/发始通话消息，
        // 改为用权威 roomId 补发取消（1v1 cancel / group hangup），确保对端止铃。
        if (onGoingCallStateManager.isInitiatorPreConnectCancelled()) {
            reCancelAfterInitiatorExit(callType, roomId ?: "", callerId)
            return false
        }
        startCallRingTone(callType)
        roomId?.let { rid ->
            addStartCallData(forWhat, callerId, callIntent.roomName, rid, callType, createdAt)
        }
        sendStartCallTextMessage(forWhat, callType, systemShowTimestamp)
        return true
    }

    /**
     * best-effort 快路径：仅当建房响应恰好赶在 teardown 取消 viewModelScope 之前到达时，才用已知的
     * 权威 [roomId] 补发结束信令（1v1 cancel，group hangup=end-for-all；窗口 W 内尚无远端加入，
     * callUidList 为空）。语义与 [com.difft.android.call.handler.CallExitHandler.handleInitiatorPreConnectExit] 对齐。
     *
     * 注意：这**不是保证**。快速退出时响应通常晚于 onEndCall() 取消 viewModelScope 并释放房间，届时
     * 本方法不会执行、roomId 也无从得知。该窗口的权威远端止铃由**后端**负责——把退出时携带 clientCallId
     * 的取消当作墓碑，使后续用同一 clientCallId 建房不再响铃。本路径只在能生效时缩短窗口，不承担正确性。
     */
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
            // The type already in effect, not the pre-join one: this entry can be created either
            // before or after `Connected` resolves the authoritative type, and CallExitHandler reads
            // CallData.type to choose LEAVE vs END. Created first → the resolve writes back into this
            // entry; created second → it picks the resolved value up here. Both orders converge.
            // Deliberately scoped to this field: `callType` still drives `forWhat` (E2EE key routing)
            // and the outgoing start-call message, neither of which may follow the server value.
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
