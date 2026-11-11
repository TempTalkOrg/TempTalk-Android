package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.state.CriticalAlertCallGate
import com.difft.android.messageserialization.db.store.DBRoomStore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton










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

    
    fun clearForJoin(conversationId: String) {
        synchronized(lockFor(conversationId)) { dbRoomStore.clearCriticalAlert(conversationId) }
    }
}
