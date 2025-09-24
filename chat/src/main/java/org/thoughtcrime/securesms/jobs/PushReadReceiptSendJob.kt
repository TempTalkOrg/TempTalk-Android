package org.thoughtcrime.securesms.jobs

import com.difft.android.PushReadReceiptSendJobFactory
import com.difft.android.base.log.lumberjack.L
import difft.android.messageserialization.For
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.EntryPointAccessors
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobmanager.Data
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.SyncMessageKt
import org.whispersystems.signalservice.internal.push.receiptMessage
import java.util.LinkedList
import java.util.concurrent.TimeUnit


class PushReadReceiptSendJob @AssistedInject constructor(
    @Assisted
    parameters: Parameters?,
    @Assisted("recipientId")
    private val recipientId: String,
    @Assisted
    private val messageSentTimestamps: List<Long>,
    @Assisted
    private val readPosition1: SignalServiceProtos.ReadPosition,
    @Assisted
    private val mode: SignalServiceProtos.Mode,
    @Assisted("conversationId")
    private val conversationId: String,
    private val newSignalServiceMessageSender: NewSignalServiceMessageSender,
    private val gson: Gson,
) : PushSendJob(parameters ?: buildParameters(recipientId)) {

    private val room: For by lazy {
        val groupId = readPosition1.groupId?.toString(Charsets.UTF_8)
        if (groupId.isNullOrEmpty()) {
            For.Account(recipientId)
        } else {
            For.Group(groupId)
        }
    }

    override fun serialize(): Data {
        val builder = Data.Builder()
            .putString(KEY_RECIPIENT_ID, recipientId)
            .putLongArray(KEY_MESSAGE_SENT_TIMESTAMPS, messageSentTimestamps.toLongArray())
            .putString(KEY_MESSAGE_READ_POSITION, gson.toJson(readPosition1))
            .putString(KEY_MESSAGE_MESSAGE_MODE, gson.toJson(mode))
            .putString(KEY_MESSAGE_CONVERSATION_ID, conversationId)
        return builder.build()
    }

    override fun getFactoryKey(): String {
        return KEY
    }

    override fun onAdded() {
    }

    public override fun onPushSend() {
        try {
            //change upon to dsl builder
            val receiptMessage = receiptMessage {
                type = SignalServiceProtos.ReceiptMessage.Type.READ
                this.timestamp.addAll(messageSentTimestamps)
                readPosition = readPosition1
                messageMode = mode
            }
            L.i { "[Message][PushReadReceiptSendJob] send read receipt message to-> $recipientId, RetryCount: $runAttempt" }

            val readMessages: MutableList<SignalServiceProtos.SyncMessage.Read> = LinkedList()
            readMessages.add(
                SyncMessageKt.read {
                    sender = recipientId
                    timestamp = messageSentTimestamps.first()
                    this.readPosition = readPosition1
                    messageMode = mode
                }
            )
            newSignalServiceMessageSender.sendReceipt(
                For.Account(recipientId),
                room,
                receiptMessage,
                readMessages,
            )
        } catch (e: Exception) {
            L.e { "[Message][PushReadReceiptSendJob] send read receipt message exception -> ${e.stackTraceToString()}" }
            if (onShouldRetry(e)) {
                throw e
            }
        }
    }

    override fun onFailure() {
        L.w { "[Message][PushReadReceiptSendJob] Job failed - Target: $recipientId, Final retry count: ${runAttempt}, JobID: $id" }
    }

    class Factory : Job.Factory<PushReadReceiptSendJob> {
        @dagger.hilt.EntryPoint
        @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
        interface EntryPoint {
            fun pushReadReceiptSendJobFactory(): PushReadReceiptSendJobFactory
            val gson: Gson
        }

        override fun create(parameters: Parameters, data: Data): PushReadReceiptSendJob {
            val gson = EntryPointAccessors.fromApplication(
                ApplicationDependencies.getApplication(),
                EntryPoint::class.java
            ).gson
            val recipientId = data.getString(KEY_RECIPIENT_ID)
            val timeStamps = data.getLongArray(KEY_MESSAGE_SENT_TIMESTAMPS).toList()
            val readPosition = gson.fromJson(
                data.getString(KEY_MESSAGE_READ_POSITION),
                SignalServiceProtos.ReadPosition::class.java
            )
            val mode = gson.fromJson(
                data.getString(KEY_MESSAGE_MESSAGE_MODE),
                SignalServiceProtos.Mode::class.java
            )
            val conversationId = data.getString(KEY_MESSAGE_CONVERSATION_ID)

            return EntryPointAccessors.fromApplication(
                ApplicationDependencies.getApplication(),
                EntryPoint::class.java
            ).pushReadReceiptSendJobFactory().create(
                parameters,
                recipientId,
                timeStamps,
                readPosition,
                mode,
                conversationId
            )
        }
    }

    companion object {
        const val KEY = "PushReadReceiptSendJob"
        private const val KEY_RECIPIENT_ID = "recipient_id"
        private const val KEY_MESSAGE_SENT_TIMESTAMPS = "message_time_stamps"
        private const val KEY_MESSAGE_READ_POSITION = "read_position"
        private const val KEY_MESSAGE_MESSAGE_MODE = "message_mode"
        private const val KEY_MESSAGE_CONVERSATION_ID = "conversation_id"

        private fun buildParameters(recipientId: String): Parameters {
            return Parameters.Builder()
                .setQueue("[${KEY}::${recipientId}]")
                .setLifespan(TimeUnit.DAYS.toMillis(1))
                .setMaxAttempts(Parameters.UNLIMITED)
                .addConstraint(NetworkConstraint.KEY)
                .build()
        }
    }
}
