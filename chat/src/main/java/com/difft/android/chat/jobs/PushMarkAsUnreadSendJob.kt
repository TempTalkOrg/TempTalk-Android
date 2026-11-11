package com.difft.android.chat.jobs

import com.difft.android.PushMarkAsUnreadSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingGate
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
    messagingGate: MessagingGate,
) : PushSendJob(parameters ?: buildParameters(roomId), messagingGate) {

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
        val admission = admitSend() ?: return
        L.i { "[MarkUnread][PushMarkAsUnreadSendJob] send flag=${protoFlag.name} room=$roomId attempt=$runAttempt" }
        val success = try {
            newSignalServiceMessageSender.sendMarkAsUnread(room, protoFlag, System.currentTimeMillis(), admission).success
        } catch (e: NoValidRecipientKeysException) {
            L.w { "[MarkUnread][PushMarkAsUnreadSendJob] permanent failure, dropping job room=$roomId: ${e.stackTraceToString()}" }
            return
        } catch (e: Exception) {
            L.e { "[MarkUnread][PushMarkAsUnreadSendJob] send exception room=$roomId: ${e.stackTraceToString()}" }
            throw e
        }
        if (success == null) {
            
            
            L.w { "[MarkUnread][PushMarkAsUnreadSendJob] send failed without exception room=$roomId, dropping job" }
            return
        }
        
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

        
        fun queueFor(roomId: String): String = "[$KEY::$roomId]"

        
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
