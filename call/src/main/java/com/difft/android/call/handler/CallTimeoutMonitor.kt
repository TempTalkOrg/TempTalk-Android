package com.difft.android.call.handler

import android.content.Context
import android.content.Intent
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.CallStatus
import com.difft.android.call.manager.CallTimeoutManager
import com.difft.android.call.state.OnGoingCallStateManager

/**
 * Encapsulates the call-level timeout state machine that was previously in
 * `LCallViewModel` (the `TimeoutCheckState` + `start1V1CallTimeout` +
 * `cancelCallTimeoutCheck` + `sendTimeoutBroadcast` trio).
 *
 * Named `Monitor` to avoid confusion with the lower-level
 * [CallTimeoutManager] which is the actual execution backend.
 */
class CallTimeoutMonitor(
    private val appContext: Context,
    private val roomCtl: CallRoomController,
    private val callTimeoutManager: CallTimeoutManager,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val callRole: CallRole,
    private val roomIdGetter: () -> String?,
) {

    private enum class State { NONE, PARTICIPANT_LEAVE, ONGOING_CALL }

    private var state: State = State.NONE

    /** Kick off a 1-on-1 call timeout when only the local participant is in the room. */
    fun start1V1Timeout(rid: String) {
        roomCtl.updateCallStatus(
            if (callRole == CallRole.CALLER) CallStatus.CALLING else CallStatus.JOINING
        )
        state = State.ONGOING_CALL
        callTimeoutManager.checkCallWithTimeout(
            CallTimeoutManager.CallState.ONGOING_CALL,
            CallTimeoutManager.DEF_ONGOING_CALL_TIMEOUT,
            rid,
        ) { sendTimeoutBroadcast(rid) }
    }

    /**
     * Handle a participant disconnection. Only arms a timeout in 1-on-1 calls
     * while we are still in-calling.
     */
    fun onParticipantDisconnected(isOneOnOne: Boolean) {
        if (!isOneOnOne) return
        if (!onGoingCallStateManager.isInCalling()) return
        val rid = roomIdGetter() ?: return
        state = State.PARTICIPANT_LEAVE
        callTimeoutManager.checkCallWithTimeout(
            CallTimeoutManager.CallState.LEAVE_CALL,
            CallTimeoutManager.DEF_LEAVE_CALL_TIMEOUT,
            rid,
        ) { status -> if (status) sendTimeoutBroadcast(rid) }
    }

    /** Cancel any active timeout check. Idempotent. */
    fun cancelIfActive() {
        if (state == State.NONE) return
        roomIdGetter()?.takeIf { it.isNotEmpty() }?.let { callTimeoutManager.cancelCallWithTimeout(it) }
        state = State.NONE
    }

    private fun sendTimeoutBroadcast(roomId: String) {
        val currentRoomId = onGoingCallStateManager.getCurrentRoomId()
        if (!onGoingCallStateManager.isInCalling() ||
            onGoingCallStateManager.isInCallEnding() ||
            currentRoomId == null ||
            currentRoomId != roomId
        ) {
            L.i {
                "[Call] CallTimeoutMonitor skip timeout broadcast. " +
                    "inCalling=${onGoingCallStateManager.isInCalling()} " +
                    "isEnding=${onGoingCallStateManager.isInCallEnding()} " +
                    "currentRoomId=$currentRoomId roomId=$roomId"
            }
            return
        }
        L.i {
            "[Call] CallTimeoutMonitor send timeout broadcast. " +
                "roomId=$roomId role=${callRole.type} status=${roomCtl.callStatus.value} " +
                "callType=${roomCtl.callType.value} timeoutState=$state"
        }
        val intent = Intent(LCallConstants.CALL_ONGOING_TIMEOUT).apply {
            putExtra(LCallConstants.BUNDLE_KEY_ROOM_ID, roomId)
            setPackage(ApplicationHelper.instance.packageName)
        }
        appContext.sendBroadcast(intent)
    }
}
