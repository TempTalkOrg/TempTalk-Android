package com.difft.android.chat.message

import com.difft.android.PushMarkAsUnreadSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobs.PushMarkAsUnreadSendJob
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import difft.android.messageserialization.For
import difft.android.messageserialization.model.MARK_UNREAD_FLAG_CLEAR
import difft.android.messageserialization.model.MARK_UNREAD_FLAG_READ
import difft.android.messageserialization.model.MARK_UNREAD_FLAG_UNREAD
import difft.android.messageserialization.model.resolveIncomingMarkUnread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton













@Singleton
class ConversationUnreadMarker @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    private val dbMessageStore: DBMessageStore,
    private val readReceiptDispatcher: ReadReceiptDispatcher,
    private val openTracker: ConversationOpenTracker,
    private val jobFactory: PushMarkAsUnreadSendJobFactory,
) {
    



    fun onConversationOpened(room: For) = openTracker.onOpened(room.id)

    fun onConversationClosed(room: For) = openTracker.onClosed(room.id)

    



    suspend fun clearOnOpen(room: For) = withContext(Dispatchers.IO) {
        try {
            clearIfMarkedUnread(room)
        } catch (e: Exception) {
            L.e { "[MarkUnread] clear on open failed room=${room.id}: ${e.stackTraceToString()}" }
        }
    }

    fun markAsUnread(room: For) {
        L.i { "[MarkUnread] markAsUnread room=${room.id}" }
        enqueue(room, MARK_UNREAD_FLAG_UNREAD)
    }

    





    suspend fun markAsRead(room: For) = withContext(Dispatchers.IO) {
        try {
            val lastActiveTime = dbRoomStore.createRoomIfNotExist(room).lastActiveTime
            L.i { "[MarkUnread] markAsRead room=${room.id} upTo=$lastActiveTime" }
            if (lastActiveTime > 0) {
                val readAt = ServerTimeProvider.nowMillis()
                
                readReceiptDispatcher.send(room, lastActiveTime, readAt)
                dbRoomStore.updateMessageReadPosition(room, lastActiveTime)
                dbMessageStore.updateMessageReadTime(room.id, lastActiveTime, readAt)
            }
            enqueue(room, MARK_UNREAD_FLAG_READ)
        } catch (e: Exception) {
            L.e { "[MarkUnread] markAsRead failed room=${room.id}: ${e.stackTraceToString()}" }
        }
    }

    







    suspend fun onIncomingSync(room: For, flag: Int, serverTimestamp: Long) = withContext(Dispatchers.IO) {
        try {
            val (storedFlag, storedAt) = dbRoomStore.getMarkUnread(room)
            val readPosition = dbRoomStore.getMessageReadPosition(room)
            val resolved = resolveIncomingMarkUnread(flag, serverTimestamp, storedAt, readPosition)
            L.i { "[MarkUnread] incoming sync room=${room.id} flag=$flag at=$serverTimestamp stored=$storedFlag/$storedAt readPos=$readPosition -> $resolved" }
            if (resolved == null) return@withContext

            if (resolved == MARK_UNREAD_FLAG_UNREAD && openTracker.isOpen(room.id)) {
                
                dbRoomStore.updateMarkUnread(room, MARK_UNREAD_FLAG_CLEAR, serverTimestamp)
                enqueue(room, MARK_UNREAD_FLAG_CLEAR)
                return@withContext
            }

            dbRoomStore.updateMarkUnread(room, resolved, serverTimestamp)
        } catch (e: Exception) {
            L.e { "[MarkUnread] incoming sync failed room=${room.id}: ${e.stackTraceToString()}" }
        }
    }

    




    private suspend fun clearIfMarkedUnread(room: For) {
        val (flag, _) = dbRoomStore.getMarkUnread(room)
        val unreadQueued = flag != MARK_UNREAD_FLAG_UNREAD &&
            ApplicationDependencies.getJobManager().findJobsInQueue(PushMarkAsUnreadSendJob.queueFor(room.id))
                .any { PushMarkAsUnreadSendJob.flagOf(it) == MARK_UNREAD_FLAG_UNREAD }
        if (flag != MARK_UNREAD_FLAG_UNREAD && !unreadQueued) return
        L.i { "[MarkUnread] clear on open room=${room.id} storedFlag=$flag unreadQueued=$unreadQueued" }
        enqueue(room, MARK_UNREAD_FLAG_CLEAR)
    }

    private fun enqueue(room: For, flag: Int) {
        ApplicationDependencies.getJobManager().add(
            jobFactory.create(
                parameters = null,
                roomId = room.id,
                isGroup = room is For.Group,
                flag = flag,
            )
        )
    }
}
