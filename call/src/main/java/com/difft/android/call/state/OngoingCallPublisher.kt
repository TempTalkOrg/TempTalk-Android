package com.difft.android.call.state

import androidx.annotation.MainThread
import com.difft.android.base.log.lumberjack.L







internal class OngoingCallPublisher(
    private val sessionId: Long,
    private val ogsm: OnGoingCallStateManager,
) {
    
    @Volatile private var dropLogged = false

    
    fun publish(block: OnGoingCallStateManager.MirrorWriter.() -> Unit) {
        if (ogsm.publish(sessionId, block)) return
        if (!dropLogged) {
            dropLogged = true
            L.i { "[Call][Projection] write dropped: session=$sessionId not owner" }
        }
    }

    
    @MainThread
    fun bind() = ogsm.bindSession(sessionId)

    
    @MainThread
    fun beginPresence() {
        bind()
        publish { setIsInCalling(true) }
    }

    
    @MainThread
    fun endPresence() {
        if (!ogsm.endPresence(sessionId)) L.w { "[Call][Projection] endPresence skipped: session=$sessionId not owner" }
    }
}
