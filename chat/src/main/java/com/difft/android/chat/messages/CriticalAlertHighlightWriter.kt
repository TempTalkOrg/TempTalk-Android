package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.state.CriticalAlertCallGate
import com.difft.android.messageserialization.db.store.DBRoomStore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the critical-alert conversation highlight for #1381: an alert writes it unless the alert is
 * for the call being joined or already active, and a join clears it ([clearForJoin]).
 *
 * Per conversation, "check -> write" and the join's clear run under one lock, and a join publishes its
 * marker before it clears. Either the write's check sees the marker, or the write finished first and
 * the join's clear removes it; an alert for another call that writes after the join's clear keeps its
 * highlight. Called from message-processing and join threads, never from main.
 */
@Singleton
class CriticalAlertHighlightWriter @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    private val criticalAlertCallGate: CriticalAlertCallGate,
) {

    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(conversationId: String): Any = locks.computeIfAbsent(conversationId) { Any() }

    fun highlightIfUnread(conversationId: String, roomId: String?, serverTimestamp: Long) {
        synchronized(lockFor(conversationId)) {
            if (criticalAlertCallGate.isForCurrentCall(conversationId, roomId)) {
                L.i { "[CriticalAlert] highlight skipped reason=current_call roomId=$roomId" }
                return
            }
            dbRoomStore.setCriticalAlertIfUnread(conversationId, serverTimestamp)
        }
    }

    /** A join's clear; the join's gate marker must already be published. */
    fun clearForJoin(conversationId: String) {
        synchronized(lockFor(conversationId)) { dbRoomStore.clearCriticalAlert(conversationId) }
    }
}
