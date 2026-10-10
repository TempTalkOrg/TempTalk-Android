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

/**
 * Single owner of the manual "mark as unread / mark as read" state.
 *
 * Every user action turns into a `SyncMessage.markAsUnread` Job ([PushMarkAsUnreadSendJob]) so
 * the account's other devices follow; the local flag is written by that Job once the server
 * accepted the sync, using the server timestamp as the mark time (siblings do the same).
 * "Mark as read" additionally reads the room for real — read position, read time, read
 * receipts to senders, read-position sync — exactly as if the user had opened it.
 *
 * Every entry point runs its DB work on IO and swallows failures with an `L.e`: callers launch
 * these from Main-scoped coroutines (ViewModels) where an uncaught exception would crash.
 */
@Singleton
class ConversationUnreadMarker @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    private val dbMessageStore: DBMessageStore,
    private val readReceiptDispatcher: ReadReceiptDispatcher,
    private val openTracker: ConversationOpenTracker,
    private val jobFactory: PushMarkAsUnreadSendJobFactory,
) {
    /**
     * The chat screen of [room] is showing. Synchronous on purpose: it must pair with
     * [onConversationClosed] even when the owner is cleared before any launched work runs.
     */
    fun onConversationOpened(room: For) = openTracker.onOpened(room.id)

    fun onConversationClosed(room: For) = openTracker.onClosed(room.id)

    /**
     * A manual-unread mark no longer makes sense for a room the user just opened: send CLEAR,
     * also when the UNREAD sync itself is still queued (see [clearIfMarkedUnread]).
     */
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

    /**
     * Reads the whole room: everything up to the room's last activity becomes read locally,
     * senders get read receipts, other devices get the read-position sync, then the READ flag
     * sync clears any manual-unread mark they hold. The bound is the stored
     * `RoomModel.lastActiveTime` (a message time), never a list-level display time.
     */
    suspend fun markAsRead(room: For) = withContext(Dispatchers.IO) {
        try {
            val lastActiveTime = dbRoomStore.createRoomIfNotExist(room).lastActiveTime
            L.i { "[MarkUnread] markAsRead room=${room.id} upTo=$lastActiveTime" }
            if (lastActiveTime > 0) {
                val readAt = ServerTimeProvider.nowMillis()
                // Dispatch first: the dispatcher scopes its work to (storedReadPosition, lastActiveTime].
                readReceiptDispatcher.send(room, lastActiveTime, readAt)
                dbRoomStore.updateMessageReadPosition(room, lastActiveTime)
                dbMessageStore.updateMessageReadTime(room.id, lastActiveTime, readAt)
            }
            enqueue(room, MARK_UNREAD_FLAG_READ)
        } catch (e: Exception) {
            L.e { "[MarkUnread] markAsRead failed room=${room.id}: ${e.stackTraceToString()}" }
        }
    }

    /**
     * Applies a `markAsUnread` sync from another device of this account. [serverTimestamp] is
     * the envelope's server time — the same clock the sending device stored as its mark time.
     *
     * READ only stores the flag: the marking device follows up with its own read-position sync
     * carrying the exact message it read up to, which is what advances the read position here.
     * Advancing to the envelope time instead would count messages that were still in flight.
     */
    suspend fun onIncomingSync(room: For, flag: Int, serverTimestamp: Long) = withContext(Dispatchers.IO) {
        try {
            val (storedFlag, storedAt) = dbRoomStore.getMarkUnread(room)
            val readPosition = dbRoomStore.getMessageReadPosition(room)
            val resolved = resolveIncomingMarkUnread(flag, serverTimestamp, storedAt, readPosition)
            L.i { "[MarkUnread] incoming sync room=${room.id} flag=$flag at=$serverTimestamp stored=$storedFlag/$storedAt readPos=$readPosition -> $resolved" }
            if (resolved == null) return@withContext

            if (resolved == MARK_UNREAD_FLAG_UNREAD && openTracker.isOpen(room.id)) {
                // The user is looking at this room right now: answer with CLEAR instead of showing it
                // unread (iOS does the same from the open conversation).
                dbRoomStore.updateMarkUnread(room, MARK_UNREAD_FLAG_CLEAR, serverTimestamp)
                enqueue(room, MARK_UNREAD_FLAG_CLEAR)
                return@withContext
            }

            dbRoomStore.updateMarkUnread(room, resolved, serverTimestamp)
        } catch (e: Exception) {
            L.e { "[MarkUnread] incoming sync failed room=${room.id}: ${e.stackTraceToString()}" }
        }
    }

    /**
     * CLEAR is due when the stored flag is UNREAD, or when an UNREAD sync is still queued for the
     * room (marked offline, or within the round trip): the CLEAR lands behind it in the same
     * persisted queue, so the order holds even across process death.
     */
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
