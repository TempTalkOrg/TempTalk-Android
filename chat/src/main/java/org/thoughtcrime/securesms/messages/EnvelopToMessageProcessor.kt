package org.thoughtcrime.securesms.messages

import com.difft.android.base.log.lumberjack.L
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.difft.android.websocket.api.messages.SignalServiceDataClass
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class EnvelopToMessageProcessor @Inject constructor(
    private val newMessageDecryptionUtil: NewMessageDecryptionUtil,
    private val messageContentProcessor: MessageContentProcessor,
) {

    suspend fun process(envelope: Envelope, tag: String) = withContext(Dispatchers.Default) {
        return@withContext try {
            envelope.takeIf {
                envelopSizeNotExceedOneMillion(it, tag)
            }?.decrypt()?.takeIf {
                dataMessageBodyNotExceed8K(it, tag)
            }?.processContentToMessage(tag)?.compatTimestamp()
        } catch (e: Exception) {
            L.e { "[Message][${tag}] decrypt&process message ${envelope.timestamp} exception -> ${e.stackTraceToString()}" }
            FirebaseCrashlytics.getInstance().recordException(Exception("Message processing failed - timestamp: ${envelope.timestamp}, tag: $tag e:${e.stackTraceToString()}"))
            throw e
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
        val sizeExceedOneMillion = it.toByteArray().size > 1024 * 1024;
        if (sizeExceedOneMillion) {
            L.e { "[Message][${tag}] message size exceed 1 million, ignore this envelop" }
        }
        return !sizeExceedOneMillion
    }

    private fun Envelope.decrypt(): SignalServiceDataClass? {
        // 对 envelope 的 content 进行解密（针对端上加密）
//        L.i { "[Message] decrypt message -> " + "NewMessageDecryptionUtil.decrypt" }
        return newMessageDecryptionUtil.decrypt(this)

    }

    private suspend fun SignalServiceDataClass.processContentToMessage(tag: String): Result? {
        L.d { "[Message][${tag}] Start processing Message " }
        return messageContentProcessor.process(content = this, tag)?.let {
            Result(
                message = it,
                shouldShowNotification = shouldShowNotification,
                conversation = conversation
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
    )
}


