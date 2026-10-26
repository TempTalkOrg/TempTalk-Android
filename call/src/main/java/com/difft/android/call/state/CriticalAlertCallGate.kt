package com.difft.android.call.state

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells whether a critical alert belongs to the call this device is joining or is in (#1381), so
 * the alert is treated as handled instead of ringing over that call.
 *
 * A join is tracked from its entry point ([beginJoin]) until it returns ([endJoin]); a launched
 * join's session has published its presence by then. Matching is room-first: when the alert and
 * the target both know their room, only the room counts; otherwise the conversation does.
 *
 * The active call publishes its room id only after the start-call response, so a JOIN binds the
 * room it already knows to its session ([bindSessionRoom]); the hint applies only while that session
 * owns the call projection. An active call with no room yet is a START, which no room-carrying alert
 * targets, so such an alert never matches it. Alerts for any other call keep their current behaviour.
 *
 * Ordering contract: a join calls [beginJoin] before it cancels alerts, and a publisher re-checks
 * after it acted and undoes itself on a match. Either the join's cancellation removes the action or
 * the re-check sees the marker. [isForCurrentCall] is lock-free and safe on any thread.
 *
 * [shouldSilence] is the presentation predicate: [isForCurrentCall], or the alert's room has ended
 * (#1387, [CriticalAlertRoomRegistry]), so an alert still on its way to the user when its call
 * ends is dropped at the next admission point. Only presentation uses it; recording the unread
 * highlight keeps [isForCurrentCall].
 */
@Singleton
class CriticalAlertCallGate @Inject constructor(
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val roomRegistry: CriticalAlertRoomRegistry,
) {

    private class Target(val roomId: String?, val conversationId: String?)

    private class RoomHint(val sessionId: Long, val roomId: String)

    // Copy-on-write snapshot of the in-flight joins by token; the lock guards only its replacement.
    @Volatile
    private var inFlightJoins: Map<Long, Target> = emptyMap()
    private val writeLock = Any()
    private var nextToken = 0L

    @Volatile
    private var joinRoomHint: RoomHint? = null

    /** Registers an in-flight join; the returned token ends only this marker. */
    fun beginJoin(roomId: String?, conversationId: String?): Long = synchronized(writeLock) {
        val token = ++nextToken
        inFlightJoins = inFlightJoins + (token to Target(roomId, conversationId))
        token
    }

    fun endJoin(token: Long) {
        synchronized(writeLock) { inFlightJoins = inFlightJoins - token }
    }

    /** A JOIN's session [sessionId] is for [roomId]; replaces any earlier session's hint. */
    fun bindSessionRoom(sessionId: Long, roomId: String) {
        joinRoomHint = RoomHint(sessionId, roomId)
    }

    fun isForCurrentCall(conversationId: String, roomId: String?): Boolean =
        inFlightJoins.values.any { it.matches(conversationId, roomId) } ||
            activeCall()?.matchesActive(conversationId, roomId) == true

    /** The alert's call is over (#1387); an alert without a room id never ends this way. */
    fun isRoomEnded(roomId: String?): Boolean = !roomId.isNullOrEmpty() && roomRegistry.isEnded(roomId)

    /** Presentation must not show, sound or flash this alert: its call is current (#1381) or over (#1387). */
    fun shouldSilence(conversationId: String, roomId: String?): Boolean =
        isForCurrentCall(conversationId, roomId) || isRoomEnded(roomId)

    private fun activeCall(): Target? {
        if (!onGoingCallStateManager.isInCalling() || onGoingCallStateManager.isInCallEnding()) return null
        val owner = onGoingCallStateManager.ownerSession()
        // The owner's hint wins: a late room write from an ended session can land in this projection.
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
