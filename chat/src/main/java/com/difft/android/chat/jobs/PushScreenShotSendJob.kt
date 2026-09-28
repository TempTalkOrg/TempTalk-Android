package com.difft.android.chat.jobs

import com.difft.android.PushScreenShotSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.common.SendType
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import com.difft.android.chat.util.DataMessageCreator
import com.difft.android.websocket.api.NewSignalServiceMessageSender
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.TextMessage
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Sends the "took a screenshot" DataMessage and persists its row only once the server accepted it.
 *
 * Why not [PushTextSendJob]: that path writes the row up front and flips it to FAILED once its
 * attempts are exhausted. A screenshot row renders as a centered notice with no status icon and no
 * resend, so a FAILED one is invisible in the conversation yet still earns the chat list a
 * "Send failed" tag the user cannot clear (issue #1265). Sending first and writing afterwards means
 * a FAILED screenshot row can no longer come into existence.
 *
 * The row this job writes is [textMessage] itself — the SAME representation the peer and this
 * account's other devices store for the same event: a TYPE_TEXT row carrying `screenShotJson`,
 * rendered through `VIEW_TYPE_SCREENSHOT`. Android deliberately unified screenshot notices onto the
 * text representation (PR #226) rather than a separate notify row; iOS models it as an info message
 * instead, and that difference is local to each client's rendering.
 *
 * Retries are unbounded within the one-day lifespan — the notice has no resend UI, so a spent
 * attempt budget would drop it silently; the network constraint means attempts are only ever spent
 * on real send failures, not on being offline.
 *
 * [textMessage] is serialized with the job so a process death mid-retry resumes with the same
 * timestamp and id. Its `timeStamp` is the DataMessage timestamp AND the id seed
 * (`generateMessageId(timeStamp, myId, DEFAULT_DEVICE_ID)`), which is exactly the id this device's
 * own sync echo of a 1v1 send carries — so `putWhenNonExist` keeps whichever of the two lands first
 * and skips the other. Both are the same row type in the same Sent state, so the surviving row is
 * identical either way. Same three-way timestamp alignment contract [PushForwardNoticeSendJob] and
 * [PushActivityNoticeSendJob] rely on.
 *
 * A group row is deliberately persisted WITHOUT `receiverIds`, unlike [PushTextSendJob.onAdded].
 * That field feeds only the read-status list of the message detail page, and a screenshot row has
 * no detail page: it renders through the Notify view holder, whose interaction callbacks are empty.
 * Populating it here would also be ineffective — the JobController serializes a job before
 * `onAdded` runs and executes a deserialized copy, so a field set in `onAdded` never reaches the
 * persisted row.
 */
class PushScreenShotSendJob @AssistedInject constructor(
    @Assisted parameters: Parameters?,
    @Assisted private val textMessage: TextMessage,
    private val messageSender: NewSignalServiceMessageSender,
    private val dataMessageCreator: DataMessageCreator,
    private val messageStore: MessageStore,
    private val gson: Gson,
) : PushSendJob(parameters ?: buildParameters(textMessage.forWhat)) {

    override fun serialize(): Data = Data.Builder()
        .putString(KEY_MESSAGE_OUT, gson.toJson(textMessage))
        .build()

    override fun getFactoryKey(): String = KEY

    override fun onAdded() {}

    public override suspend fun onPushSend() {
        val dataMessage = dataMessageCreator.createFrom(textMessage)
        val notification = buildTextMessageNotification(textMessage, gson).toNewNotification()

        L.i {
            "[Screenshot][PushScreenShotSendJob] send timeStamp=${textMessage.timeStamp}, " +
                "target=${textMessage.forWhat.id}, isGroup=${textMessage.forWhat is For.Group}, " +
                "attempt=$runAttempt"
        }

        val result = try {
            messageSender.sendDataMessage(
                textMessage.forWhat,
                textMessage.forWhat,
                dataMessage,
                notification
            )
        } catch (e: NonSuccessfulResponseCodeException) {
            // A 4xx is the server's final answer (430 / 432 non-friend limit / 404 offline or
            // deregistered), and the exception is an IOException, so without this branch
            // onShouldRetry would keep retrying it for the whole one-day lifespan. Permanent →
            // swallow so the job leaves the queue, logged at W so production can confirm it
            // stopped on purpose. Same shape as PushReadReceiptSendJob's permanent-failure catch.
            //
            // The permanent set is exactly PushTextSendJob's. Every other code keeps the normal
            // retry path: 5xx, and the retryable 4xx family — RateLimitException (413/429) and
            // ProofRequiredException (428), whose Retry-After backoff lives in
            // PushSendJob.getNextRunAttemptBackoff and must stay reachable.
            //
            // Deliberately NOT replicating PushTextSendJob's 430/432/404 local system messages:
            // an automatic notice must not spawn "offline" / "non-friend limit" rows the user
            // never asked for.
            if (e.code !in PERMANENT_REJECTION_CODES) throw e
            L.w {
                "[Screenshot][PushScreenShotSendJob] permanent rejection code=${e.code}, " +
                    "target=${textMessage.forWhat.id}, timeStamp=${textMessage.timeStamp}, dropping"
            }
            return
        }

        val success = result.success
        if (success == null) {
            // Defensive branch: sendDataMessage either returns a success or throws, so this is not
            // reached in practice. A rejection that somehow returned no success leaves no trace on
            // this device.
            L.w {
                "[Screenshot][PushScreenShotSendJob] send rejected timeStamp=${textMessage.timeStamp}, " +
                    "target=${textMessage.forWhat.id}"
            }
            return
        }

        // Server accepted -> persist the row on THIS device, carrying the server's own ordering
        // keys. This is what PushTextSendJob.updateMessage(Sent) wrote for the same message, minus
        // the room sendStatus / sendingStatus writes: the row is only ever written as Sent, so
        // neither aggregate has anything to escalate. DB failure is isolated (do NOT rethrow: a
        // retry would re-send the message to peers who already have it).
        try {
            textMessage.sendType = SendType.Sent.rawValue
            textMessage.systemShowTimestamp = success.systemShowTimestamp.takeIf { it > 0 } ?: textMessage.timeStamp
            textMessage.notifySequenceId = success.notifySequenceId
            textMessage.sequenceId = success.sequenceId
            messageStore.putWhenNonExist(textMessage)
        } catch (ce: CancellationException) {
            throw ce
        } catch (dbE: Exception) {
            L.e {
                "[Screenshot][PushScreenShotSendJob] local row insert failed, " +
                    "message already sent: ${dbE.stackTraceToString()}"
            }
        }
    }

    override fun onFailure() {
        L.w {
            "[Screenshot][PushScreenShotSendJob] Job failed - target=${textMessage.forWhat.id}, " +
                "timeStamp=${textMessage.timeStamp}, attempts=$runAttempt, JobID=$id"
        }
    }

    class Factory : Job.Factory<PushScreenShotSendJob> {
        @EntryPoint
        @InstallIn(SingletonComponent::class)
        interface FactoryEntryPoint {
            fun pushScreenShotSendJobFactory(): PushScreenShotSendJobFactory
            val gson: Gson
        }

        override fun create(parameters: Parameters, data: Data): PushScreenShotSendJob {
            val entryPoint = EntryPointAccessors.fromApplication(
                ApplicationDependencies.getApplication(),
                FactoryEntryPoint::class.java
            )
            val textMessage = entryPoint.gson.fromJson(
                data.getString(KEY_MESSAGE_OUT),
                TextMessage::class.java
            )
            return entryPoint.pushScreenShotSendJobFactory().create(parameters, textMessage)
        }
    }

    companion object {
        const val KEY = "PushScreenShotSendJob"
        private const val KEY_MESSAGE_OUT = "message_out"

        /** Server's final answers, mirroring PushTextSendJob: 404 account gone, 430, 432 non-friend limit. */
        private val PERMANENT_REJECTION_CODES = setOf(404, 430, 432)

        private fun buildParameters(target: For): Parameters = Parameters.Builder()
            .setQueue("[$KEY::${target.id}]")
            .setLifespan(TimeUnit.DAYS.toMillis(1))
            .setMaxAttempts(Parameters.UNLIMITED)
            .addConstraint(NetworkConstraint.KEY)
            .build()
    }
}
