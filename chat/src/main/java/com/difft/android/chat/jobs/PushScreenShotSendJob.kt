package com.difft.android.chat.jobs

import com.difft.android.PushScreenShotSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.chat.common.SendType
import com.difft.android.chat.group.GroupUtil
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
import difft.android.messageserialization.model.TextMessage
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit









class PushScreenShotSendJob @AssistedInject constructor(
    @Assisted parameters: Parameters?,
    @Assisted private val textMessage: TextMessage,
    private val messageSender: NewSignalServiceMessageSender,
    private val dataMessageCreator: DataMessageCreator,
    private val rowWriter: ScreenShotRowWriter,
    private val gson: Gson,
    messagingGate: MessagingGate,
    private val groupUtil: GroupUtil,
) : PushSendJob(parameters ?: buildParameters(textMessage.forWhat), messagingGate) {

    override fun serialize(): Data = Data.Builder()
        .putString(KEY_MESSAGE_OUT, gson.toJson(textMessage))
        .build()

    override fun getFactoryKey(): String = KEY

    override fun onAdded() {
        try {
            rowWriter.insertPending(textMessage)
        } catch (ce: CancellationException) {
            throw ce
        } catch (dbE: Exception) {
            
            L.e {
                "[Screenshot][PushScreenShotSendJob] pending row insert failed target=${textMessage.forWhat.id} " +
                    "timeStamp=${textMessage.timeStamp}: ${dbE.stackTraceToString()}"
            }
        }
    }

    public override suspend fun onPushSend() {
        val dataMessage = dataMessageCreator.createFrom(textMessage)
        val notification = buildTextMessageNotification(textMessage, gson).toNewNotification()

        L.i {
            "[Screenshot][PushScreenShotSendJob] send timeStamp=${textMessage.timeStamp}, " +
                "target=${textMessage.forWhat.id}, isGroup=${textMessage.forWhat is For.Group}, " +
                "attempt=$runAttempt"
        }

        val admission = admitSend() ?: return
        val result = try {
            messageSender.sendDataMessage(
                textMessage.forWhat,
                textMessage.forWhat,
                dataMessage,
                notification,
                admission
            )
        } catch (e: NonSuccessfulResponseCodeException) {
            
            
            
            
            
            
            
            
            
            
            
            
            
            
            if (e.code !in PERMANENT_REJECTION_CODES) throw e
            L.w {
                "[Screenshot][PushScreenShotSendJob] permanent rejection code=${e.code}, " +
                    "target=${textMessage.forWhat.id}, timeStamp=${textMessage.timeStamp}, dropping"
            }
            withdrawRow()
            return
        }

        val success = result.success
        if (success == null) {
            
            
            L.w {
                "[Screenshot][PushScreenShotSendJob] send rejected timeStamp=${textMessage.timeStamp}, " +
                    "target=${textMessage.forWhat.id}"
            }
            withdrawRow()
            return
        }

        if (textMessage.forWhat is For.Group) groupUtil.clearExpiry(textMessage.forWhat.id, "sent")

        
        
        
        
        try {
            textMessage.sendType = SendType.Sent.rawValue
            textMessage.systemShowTimestamp = success.systemShowTimestamp.takeIf { it > 0 } ?: textMessage.timeStamp
            textMessage.notifySequenceId = success.notifySequenceId
            textMessage.sequenceId = success.sequenceId
            rowWriter.markSent(textMessage)
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
        withdrawRow()
    }

    private fun withdrawRow() {
        try {
            rowWriter.withdraw(textMessage)
        } catch (ce: CancellationException) {
            throw ce
        } catch (dbE: Exception) {
            L.e {
                "[Screenshot][PushScreenShotSendJob] row withdraw failed target=${textMessage.forWhat.id} " +
                    "timeStamp=${textMessage.timeStamp}: ${dbE.stackTraceToString()}"
            }
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

        
        private val PERMANENT_REJECTION_CODES = setOf(404, 430, 432)

        private fun buildParameters(target: For): Parameters = Parameters.Builder()
            .setQueue("[$KEY::${target.id}]")
            .setLifespan(TimeUnit.DAYS.toMillis(1))
            .setMaxAttempts(Parameters.UNLIMITED)
            .addConstraint(NetworkConstraint.KEY)
            .build()
    }
}
