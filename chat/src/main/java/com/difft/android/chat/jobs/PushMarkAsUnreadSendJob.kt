package com.difft.android.chat.jobs

import com.difft.android.PushMarkAsUnreadSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.impl.JsonDataSerializer
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.push.exceptions.NoValidRecipientKeysException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.EntryPointAccessors
import difft.android.messageserialization.For
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncMessage
import java.util.concurrent.TimeUnit

/**
 * Sends one `SyncMessage.markAsUnread` (CLEAR / UNREAD / READ) to the account's other devices
 * and, only once the server accepted it, stores the flag locally with the server timestamp as
 * the mark time — the same write-after-success rule iOS and Desktop follow, so all devices
 * agree on the mark time.
 *
 * Jobs for one room share a persisted queue, so flags apply in the order the user issued them:
 * opening a room while an UNREAD for it is still queued enqueues a CLEAR behind it (see
 * `ConversationUnreadMarker.clearOnOpen`), which survives process death. The local write keeps a
 * mark with a newer server time that arrived from another device while this sync was in flight.
 */
class PushMarkAsUnreadSendJob @AssistedInject constructor(
    @Assisted
    parameters: Parameters?,
    @Assisted("roomId")
    private val roomId: String,
    @Assisted("isGroup")
    private val isGroup: Boolean,
    @Assisted("flag")
    private val flag: Int,
    private val newSignalServiceMessageSender: NewSignalServiceMessageSender,
    private val dbRoomStore: DBRoomStore,
) : PushSendJob(parameters ?: buildParameters(roomId)) {

    private val room: For
        get() = if (isGroup) For.Group(roomId) else For.Account(roomId)

    override fun serialize(): Data = Data.Builder()
        .putString(KEY_ROOM_ID, roomId)
        .putBoolean(KEY_IS_GROUP, isGroup)
        .putInt(KEY_FLAG, flag)
        .build()

    override fun getFactoryKey(): String = KEY

    override fun onAdded() {}

    public override suspend fun onPushSend() {
        val protoFlag = SyncMessage.MarkAsUnread.Flag.forNumber(flag)
        if (protoFlag == null) {
            L.w { "[MarkUnread][PushMarkAsUnreadSendJob] unknown flag=$flag room=$roomId, dropping job" }
            return
        }
        L.i { "[MarkUnread][PushMarkAsUnreadSendJob] send flag=${protoFlag.name} room=$roomId attempt=$runAttempt" }
        val success = try {
            newSignalServiceMessageSender.sendMarkAsUnread(room, protoFlag, System.currentTimeMillis()).success
        } catch (e: NoValidRecipientKeysException) {
            L.w { "[MarkUnread][PushMarkAsUnreadSendJob] permanent failure, dropping job room=$roomId: ${e.stackTraceToString()}" }
            return
        } catch (e: Exception) {
            L.e { "[MarkUnread][PushMarkAsUnreadSendJob] send exception room=$roomId: ${e.stackTraceToString()}" }
            throw e
        }
        if (success == null) {
            // Non-throwing failure (e.g. unregistered): nothing to retry against, the local
            // state simply stays as it was — matches the siblings, which only log here.
            L.w { "[MarkUnread][PushMarkAsUnreadSendJob] send failed without exception room=$roomId, dropping job" }
            return
        }
        // The sync is already on the server: a local write failure must not re-send it.
        try {
            storeResult(success.systemShowTimestamp)
        } catch (e: Exception) {
            L.e { "[MarkUnread][PushMarkAsUnreadSendJob] local write failed room=$roomId flag=$flag: ${e.stackTraceToString()}" }
        }
    }

    private suspend fun storeResult(markedAt: Long) {
        val (storedFlag, storedAt) = dbRoomStore.getMarkUnread(room)
        if (markedAt <= storedAt) {
            L.i { "[MarkUnread][PushMarkAsUnreadSendJob] newer mark already stored room=$roomId stored=$storedFlag/$storedAt mine=$flag/$markedAt, keeping stored" }
            return
        }
        dbRoomStore.updateMarkUnread(room, flag, markedAt)
    }

    override fun onFailure() {
        L.w { "[MarkUnread][PushMarkAsUnreadSendJob] job failed room=$roomId flag=$flag attempts=$runAttempt id=$id" }
    }

    class Factory : Job.Factory<PushMarkAsUnreadSendJob> {
        @dagger.hilt.EntryPoint
        @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
        interface EntryPoint {
            fun pushMarkAsUnreadSendJobFactory(): PushMarkAsUnreadSendJobFactory
        }

        override fun create(parameters: Parameters, data: Data): PushMarkAsUnreadSendJob {
            return EntryPointAccessors.fromApplication(
                ApplicationDependencies.getApplication(),
                EntryPoint::class.java
            ).pushMarkAsUnreadSendJobFactory().create(
                parameters,
                data.getString(KEY_ROOM_ID)!!,
                data.getBoolean(KEY_IS_GROUP),
                data.getInt(KEY_FLAG)
            )
        }
    }

    companion object {
        const val KEY = "PushMarkAsUnreadSendJob"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_IS_GROUP = "is_group"
        private const val KEY_FLAG = "flag"
        private const val LIFESPAN_DAYS = 7L

        /** The per-room queue every mark job for [roomId] runs in, in submission order. */
        fun queueFor(roomId: String): String = "[$KEY::$roomId]"

        /** The flag a queued [spec] of this job carries, or `null` for other jobs / undecodable data. */
        fun flagOf(spec: JobSpec): Int? {
            if (spec.factoryKey != KEY) return null
            return runCatching { JsonDataSerializer().deserialize(spec.serializedData).getInt(KEY_FLAG) }.getOrNull()
        }

        private fun buildParameters(roomId: String): Parameters {
            return Parameters.Builder()
                .setQueue(queueFor(roomId))
                .setLifespan(TimeUnit.DAYS.toMillis(LIFESPAN_DAYS))
                .setMaxAttempts(Parameters.UNLIMITED)
                .addConstraint(NetworkConstraint.KEY)
                .build()
        }
    }
}
