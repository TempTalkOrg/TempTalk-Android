package com.difft.android.call.state

import com.difft.android.call.data.CallStatus










internal object OgsmTestState {

    
    const val DETACHED_SESSION_ID = 0L

    
    fun enterCall(ogsm: OnGoingCallStateManager, roomId: String?, sessionId: Long = DETACHED_SESSION_ID) {
        write(ogsm, sessionId) {
            setIsInCalling(true)
            setCurrentRoomId(roomId)
        }
    }

    
    fun setCallStatus(ogsm: OnGoingCallStateManager, status: CallStatus, sessionId: Long = DETACHED_SESSION_ID) {
        write(ogsm, sessionId) { updateCallStatus(status) }
    }

    
    fun write(ogsm: OnGoingCallStateManager, sessionId: Long, block: OnGoingCallStateManager.MirrorWriter.() -> Unit) {
        ogsm.bindSession(sessionId)
        check(ogsm.publish(sessionId, block)) { "write for session $sessionId was not applied" }
    }
}
