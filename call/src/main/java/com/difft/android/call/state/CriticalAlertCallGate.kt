package com.difft.android.call.state

import javax.inject.Inject
import javax.inject.Singleton























@Singleton
class CriticalAlertCallGate @Inject constructor(
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val roomRegistry: CriticalAlertRoomRegistry,
) {

    private class Target(val roomId: String?, val conversationId: String?)

    private class RoomHint(val sessionId: Long, val roomId: String)

    
    @Volatile
    private var inFlightJoins: Map<Long, Target> = emptyMap()
    private val writeLock = Any()
    private var nextToken = 0L

    @Volatile
    private var joinRoomHint: RoomHint? = null

    
    fun beginJoin(roomId: String?, conversationId: String?): Long = synchronized(writeLock) {
        val token = ++nextToken
        inFlightJoins = inFlightJoins + (token to Target(roomId, conversationId))
        token
    }

    fun endJoin(token: Long) {
        synchronized(writeLock) { inFlightJoins = inFlightJoins - token }
    }

    
    fun bindSessionRoom(sessionId: Long, roomId: String) {
        joinRoomHint = RoomHint(sessionId, roomId)
    }

    fun isForCurrentCall(conversationId: String, roomId: String?): Boolean =
        inFlightJoins.values.any { it.matches(conversationId, roomId) } ||
            activeCall()?.matchesActive(conversationId, roomId) == true

    
    fun isRoomEnded(roomId: String?): Boolean = !roomId.isNullOrEmpty() && roomRegistry.isEnded(roomId)

    
    fun shouldSilence(conversationId: String, roomId: String?): Boolean =
        isForCurrentCall(conversationId, roomId) || isRoomEnded(roomId)

    private fun activeCall(): Target? {
        if (!onGoingCallStateManager.isInCalling() || onGoingCallStateManager.isInCallEnding()) return null
        val owner = onGoingCallStateManager.ownerSession()
        
        val room = joinRoomHint?.takeIf { owner != null && it.sessionId == owner }?.roomId
            ?: onGoingCallStateManager.getCurrentRoomId()?.takeIf { it.isNotEmpty() }
        return Target(room, onGoingCallStateManager.getConversationId())
    }

    private fun Target.matchesActive(alertConversationId: String, alertRoomId: String?): Boolean =
        if (!alertRoomId.isNullOrEmpty() && roomId == null) false else matches(alertConversationId, alertRoomId)

    private fun Target.matches(alertConversationId: String, alertRoomId: String?): Boolean =
        if (!alertRoomId.isNullOrEmpty() && !roomId.isNullOrEmpty()) {
            alertRoomId == roomId
        } else {
            alertConversationId == conversationId
        }
}
