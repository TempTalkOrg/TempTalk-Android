package com.difft.android.call.state

import com.difft.android.call.data.CallStatus

/**
 * The one place tests write [OnGoingCallStateManager]'s session fields directly (presence, room id,
 * status) to set up a precondition. Production writes them through the call session's fenced
 * publisher; these helpers go through the same fence.
 *
 * `sessionId` names the session the write is made for: a test with a real `CallSession` passes its
 * `id` (already the owner, so the bind is a no-op), a test without one keeps the default, which
 * becomes the owner.
 */
internal object OgsmTestState {

    /** Not a real session's id (those start at 1), nor the manager's "no owner" value. */
    const val DETACHED_SESSION_ID = 0L

    /** The page is up for a call in [roomId] (`isInCalling` true, `currentRoomId` = [roomId]). */
    fun enterCall(ogsm: OnGoingCallStateManager, roomId: String?, sessionId: Long = DETACHED_SESSION_ID) {
        write(ogsm, sessionId) {
            setIsInCalling(true)
            setCurrentRoomId(roomId)
        }
    }

    /** The session's call status as the foreground service reads it. */
    fun setCallStatus(ogsm: OnGoingCallStateManager, status: CallStatus, sessionId: Long = DETACHED_SESSION_ID) {
        write(ogsm, sessionId) { updateCallStatus(status) }
    }

    /** Binds [sessionId] as the owner and runs [block] through the fence. */
    fun write(ogsm: OnGoingCallStateManager, sessionId: Long, block: OnGoingCallStateManager.MirrorWriter.() -> Unit) {
        ogsm.bindSession(sessionId)
        check(ogsm.publish(sessionId, block)) { "write for session $sessionId was not applied" }
    }
}
