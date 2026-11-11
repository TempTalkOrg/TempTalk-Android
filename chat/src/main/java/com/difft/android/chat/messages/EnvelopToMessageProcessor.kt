package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.monitor.DeliveryChannel
import com.difft.android.base.monitor.KeepAliveHealthCounters
import com.difft.android.chat.group.GroupUtil
import com.difft.android.base.monitor.LateDeliveryTracker
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.google.gson.JsonSyntaxException
import com.google.protobuf.InvalidProtocolBufferException
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.TextMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import uniffi.dtproto.DtProtoException
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class EnvelopToMessageProcessor @Inject constructor(
    private val newMessageDecryptionUtil: NewMessageDecryptionUtil,
    private val messageContentProcessor: MessageContentProcessor,
    private val dbMessageStore: DBMessageStore,
    private val healthCounters: KeepAliveHealthCounters,
    private val lateDeliveryTracker: LateDeliveryTracker,
    private val groupUtil: GroupUtil,
) {

    companion object {
        
        private val LIVE_DELIVERY_CHANNELS = mapOf(
            "message" to DeliveryChannel.WS,
            "fcm" to DeliveryChannel.FCM,
            "PendingMessageHelper" to DeliveryChannel.PULL
        )
    }

    




















    suspend fun process(envelope: Envelope, tag: String, admission: MessagingTicket): EnvelopeProcessResult {
        if (admission.isClosed) {
            L.e { "[MigrationPause] event=process_without_ticket tag=$tag ts=${envelope.timestamp}" }
            return EnvelopeProcessResult.Paused
        }
        return processAdmitted(envelope, tag, admission)
    }

    private suspend fun processAdmitted(
        envelope: Envelope,
        tag: String,
        admission: MessagingTicket,
    ): EnvelopeProcessResult = withContext(Dispatchers.IO) {
        try {
            val result = envelope
                .takeIf { envelopSizeNotExceedOneMillion(it, tag) }
                ?.decrypt()
                ?.takeIf { dataMessageBodyNotExceed8K(it, tag) }
                ?.processContentToMessage(tag, admission)
                ?.compatTimestamp()
            val inserted = result?.let { dbMessageStore.putWhenNonExist(it.message) } ?: 0
            if (inserted > 0 && result != null) {
                result.groupExpiryClearReason?.let { groupUtil.clearExpiry(result.conversation.id, it) }
            }
            val channel = LIVE_DELIVERY_CHANNELS[tag]
            
            
            if (inserted > 0 && channel != null) {
                
                runCatching { healthCounters.onEnvelopeDelivered(envelope.systemShowTimestamp, channel) }
                runCatching { lateDeliveryTracker.onEnvelopeDelivered(envelope.systemShowTimestamp) }
            }
            EnvelopeProcessResult.Success(result)
        } catch (e: CancellationException) {
            
            
            throw e
        } catch (e: Throwable) {
            
            
            if (e is OutOfMemoryError || e is StackOverflowError) throw e
            classify(envelope, tag, e)
        }
    }

    











    private fun classify(envelope: Envelope, tag: String, e: Throwable): EnvelopeProcessResult {
        val reason: DropReason? = when (e) {
            
            is DtProtoException.DecryptMessageDataException -> DropReason.DECRYPTION_FAILED
            
            is InvalidProtocolBufferException -> DropReason.DECRYPTION_DATA_CORRUPT
            
            is JsonSyntaxException -> DropReason.MALFORMED_NOTIFY_JSON
            
            
            is Base64DecodeException -> DropReason.BASE64_DECODE_FAILED
            
            
            else -> null
        }
        return if (reason != null) {
            
            
            
            EnvelopeProcessResult.PermanentFailure(reason, e)
        } else {
            L.e {
                "[Message][$tag] TRANSIENT failure ts=${envelope.timestamp} err=${e.stackTraceToString()}"
            }
            EnvelopeProcessResult.TransientFailure(e)
        }
    }

    private fun dataMessageBodyNotExceed8K(it: SignalServiceDataClass, tag: String): Boolean {
        val dataMessageBodyExceed8K =
            (it.signalServiceContent?.dataMessage?.body?.length
                ?: 0) > 8 * 1024
        if (dataMessageBodyExceed8K) {
            L.e { "[Message][${tag}] dataMessage body exceed 8K, ignore this envelop" }
        }
        return !dataMessageBodyExceed8K
    }

    private fun envelopSizeNotExceedOneMillion(it: Envelope, tag: String): Boolean {
        val sizeExceedOneMillion = it.toByteArray().size > 1024 * 1024
        if (sizeExceedOneMillion) {
            L.e { "[Message][${tag}] message size exceed 1 million, ignore this envelop" }
        }
        return !sizeExceedOneMillion
    }

    private fun Envelope.decrypt(): SignalServiceDataClass? {
        return newMessageDecryptionUtil.decrypt(this)
    }

    private suspend fun SignalServiceDataClass.processContentToMessage(tag: String, admission: MessagingTicket): Result? {
        L.d { "[Message][${tag}] Start processing Message " }
        return messageContentProcessor.process(content = this, tag, admission)?.let {
            val service = signalServiceContent
            val isSentSync = service?.hasSyncMessage() == true && service.syncMessage.hasSent()
            val data = when {
                service?.hasDataMessage() == true -> service.dataMessage
                isSentSync -> service?.syncMessage?.sent?.message
                else -> null
            }
            val clearReason = if (it is TextMessage && conversation is For.Group && data != null &&
                !data.hasRecall() && !data.hasReaction()
            ) {
                if (isSentSync || senderId == myId) "sent" else "incoming"
            } else null
            Result(
                message = it,
                shouldShowNotification = shouldShowNotification,
                conversation = conversation,
                groupExpiryClearReason = clearReason,
            )
        }
    }

    private fun Result.compatTimestamp(): Result {
        if (message.systemShowTimestamp == 0L) {
            message.systemShowTimestamp = message.timeStamp
        }
        return this
    }

    data class Result(
        val message: Message,
        val shouldShowNotification: Boolean,
        val conversation: For,
        val groupExpiryClearReason: String? = null,
    )
}
