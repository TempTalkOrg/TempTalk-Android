package com.difft.android.call.handler

import com.difft.android.base.call.CallRole
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.data.CallStatus
import com.difft.android.call.manager.CallTimeoutManager
import com.difft.android.call.state.OnGoingCallStateManager

/**
 * The call-level timeout state machine: arms the 1v1 ongoing-call and remote-left timeouts and
 * reports a fired one through [onTimeout] while the call is live in the timed room.
 *
 * Named `Monitor` to avoid confusion with the lower-level
 * [CallTimeoutManager] which is the actual execution backend.
 */
class CallTimeoutMonitor(
    private val roomCtl: CallRoomController,
    private val callTimeoutManager: CallTimeoutManager,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val callRole: CallRole,
    private val roomIdGetter: () -> String?,
    private val onTimeout: (roomId: String) -> Unit,
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
        ) { fireTimeout(rid) }
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
        ) { status -> if (status) fireTimeout(rid) }
    }

    /** Cancel any active timeout check. Idempotent. */
    fun cancelIfActive() {
        if (state == State.NONE) return
        roomIdGetter()?.takeIf { it.isNotEmpty() }?.let { callTimeoutManager.cancelCallWithTimeout(it) }
        state = State.NONE
    }

    private fun fireTimeout(roomId: String) {
        val currentRoomId = onGoingCallStateManager.getCurrentRoomId()
        if (!onGoingCallStateManager.isInCalling() ||
            onGoingCallStateManager.isInCallEnding() ||
            currentRoomId == null ||
            currentRoomId != roomId
        ) {
            L.i {
                "[Call] CallTimeoutMonitor skip timeout. " +
                    "inCalling=${onGoingCallStateManager.isInCalling()} " +
                    "isEnding=${onGoingCallStateManager.isInCallEnding()} " +
                    "currentRoomId=$currentRoomId roomId=$roomId"
            }
            return
        }
        L.i {
            "[Call] CallTimeoutMonitor timeout roomId=$roomId role=${callRole.type} " +
                "status=${roomCtl.callStatus.value} callType=${roomCtl.callType.value} timeoutState=$state"
        }
        onTimeout(roomId)
    }
}
