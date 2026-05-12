package com.difft.android.chat.jobs

import com.difft.android.PushReactionSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import com.difft.android.chat.util.DataMessageCreator
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.EntryPointAccessors
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.TextMessage
import java.util.concurrent.TimeUnit

open class PushReactionSendJob @AssistedInject constructor(
    @Assisted
    parameters: Parameters?,
    @Assisted
    private val textMessage: TextMessage,
    private val gson: Gson,
    private val newSignalServiceMessageSender: NewSignalServiceMessageSender,
    private val dataMessageCreator: DataMessageCreator,
    private val messageStore: MessageStore,
) : PushSendJob(parameters ?: buildParameters(textMessage)) {

    override fun serialize(): Data = Data.Builder()
        .putString(KEY_MESSAGE_OUT, gson.toJson(textMessage))
        .build()

    override fun getFactoryKey(): String = KEY

    override fun onAdded() {
        val reaction = textMessage.reactions?.firstOrNull()
        val action = if (reaction?.remove == true) "remove" else "add"
        L.i {
            "[Reaction] enqueue target=${textMessage.forWhat.id} " +
                    "emoji=${reaction?.emoji} " +
                    "action=$action " +
                    "ts=${reaction?.originTimestamp}"
        }
    }

    public override suspend fun onPushSend() {
        val reaction = textMessage.reactions?.firstOrNull()
        val action = if (reaction?.remove == true) "remove" else "add"
        L.i {
            "[Reaction] sending attempt=$runAttempt target=${textMessage.forWhat.id} " +
                    "ts=${reaction?.originTimestamp} " +
                    "action=$action"
        }
        val dataMessage = dataMessageCreator.createFrom(textMessage)
        newSignalServiceMessageSender.sendDataMessage(
            recipient = textMessage.forWhat,
            room = textMessage.forWhat,
            message = dataMessage,
            notification = null,
        )
        L.i {
            "[Reaction] sent target=${textMessage.forWhat.id} ts=${reaction?.originTimestamp} action=$action"
        }
    }

    override fun onRetry() {
        super.onRetry()
        L.w {
            "[Reaction] retry scheduled attempt=$runAttempt target=${textMessage.forWhat.id} ts=${textMessage.reactions?.firstOrNull()?.originTimestamp}"
        }
    }

    override fun onFailure() {
        val original = textMessage.reactions?.firstOrNull()
        if (original == null) {
            L.w {
                "[Reaction] permanent failure but reactions empty, nothing to rollback id=${textMessage.id}"
            }
            return
        }
        // Defensive monotonic timestamp: NTP/user clock corrections could push wall-clock
        // backwards, which would cause DBMessageStore.updateMessageReaction's LWW guard
        // (reaction.originTimestamp > existing.timeStamp) to silently drop the rollback.
        val now = System.currentTimeMillis()
        val rollbackTimestamp = maxOf(now, original.originTimestamp + 1)
        val rollback = Reaction(
            emoji = original.emoji,
            uid = original.uid,
            remove = !original.remove,
            originTimestamp = rollbackTimestamp,
            realSource = original.realSource,
        )
        // Synchronous: JobRunner already invokes onFailure() on Dispatchers.IO (see
        // JobRunner.launchIn). updateMessageReaction is non-suspend (DBMessageStore.kt:106)
        // and uses runTransaction internally. Calling it directly guarantees the rollback
        // completes before the framework finishes terminal cleanup, even if the process
        // is killed immediately after onFailure() returns.
        messageStore.updateMessageReaction(
            textMessage.forWhat.id,
            rollback,
            null,
            null,
        )
        L.w {
            "[Reaction] permanently failed, rolled back. " +
                    "target=${textMessage.forWhat.id} " +
                    "emoji=${original.emoji} " +
                    "ts=${original.originTimestamp} " +
                    "totalAttempts=${runAttempt + 1}"
        }
    }

    class Factory : Job.Factory<PushReactionSendJob> {
        @dagger.hilt.EntryPoint
        @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
        interface EntryPoint {
            fun pushReactionSendJobFactory(): PushReactionSendJobFactory
            val gson: Gson
        }

        override fun create(parameters: Parameters, data: Data): PushReactionSendJob {
            val entryPoint = EntryPointAccessors.fromApplication(
                ApplicationDependencies.getApplication(),
                EntryPoint::class.java
            )
            val textMessage = entryPoint.gson.fromJson(
                data.getString(KEY_MESSAGE_OUT),
                TextMessage::class.java
            )
            return entryPoint.pushReactionSendJobFactory().create(parameters, textMessage)
        }
    }

    companion object {
        const val KEY = "PushReactionSendJob"
        private const val KEY_MESSAGE_OUT = "message_out"
        private const val LIFESPAN_DAYS = 7L

        private fun buildParameters(textMessage: TextMessage): Parameters {
            return Parameters.Builder()
                .setQueue("[$KEY::${textMessage.forWhat.id}]")
                .setLifespan(TimeUnit.DAYS.toMillis(LIFESPAN_DAYS))
                .setMaxAttempts(Parameters.UNLIMITED)
                .addConstraint(NetworkConstraint.KEY)
                .build()
        }
    }
}
