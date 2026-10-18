package com.difft.android.call.state

import androidx.annotation.MainThread
import com.difft.android.base.log.lumberjack.L

/**
 * One call session's write handle on [OnGoingCallStateManager]. Every write the session makes to
 * the projection goes through here and lands only while [sessionId] owns it: the session binds on
 * construction, shows the call when it starts ([beginPresence]) and releases the projection when it
 * ends ([endPresence]). Writes after that, or after a newer session bound, are dropped.
 */
internal class OngoingCallPublisher(
    private val sessionId: Long,
    private val ogsm: OnGoingCallStateManager,
) {
    // First dropped write per session is logged; a timer can drop one per tick.
    @Volatile private var dropLogged = false

    /** Any thread. */
    fun publish(block: OnGoingCallStateManager.MirrorWriter.() -> Unit) {
        if (ogsm.publish(sessionId, block)) return
        if (!dropLogged) {
            dropLogged = true
            L.i { "[Call][Projection] write dropped: session=$sessionId not owner" }
        }
    }

    /** The session owns the projection from now on (called first in the session's construction). */
    @MainThread
    fun bind() = ogsm.bindSession(sessionId)

    /** Session start: the call is shown (`isInCalling`). Always re-binds. */
    @MainThread
    fun beginPresence() {
        bind()
        publish { setIsInCalling(true) }
    }

    /** Session end: resets the projection and releases it; skipped if another session owns it. */
    @MainThread
    fun endPresence() {
        if (!ogsm.endPresence(sessionId)) L.w { "[Call][Projection] endPresence skipped: session=$sessionId not owner" }
    }
}
