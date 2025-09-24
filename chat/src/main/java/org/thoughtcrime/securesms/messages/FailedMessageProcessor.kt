package org.thoughtcrime.securesms.messages

import com.difft.android.base.log.lumberjack.L
import org.difft.app.database.wcdb
import difft.android.messageserialization.MessageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBFailedMessageModel
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FailedMessageProcessor @Inject constructor(
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
    private val messageStore: MessageStore
) {
    suspend fun processFailedMessages() = withContext(Dispatchers.IO) {
        try {
            // Delay for 5 seconds before processing
            delay(5000)

            // Get all failed messages from database
            val failedMessages = wcdb.failedMessage.allObjects

            if (failedMessages.isEmpty()) {
                L.i { "[FailedMessageProcessor] No failed messages to process." }
                return@withContext
            }

            L.i { "[FailedMessageProcessor] Processing ${failedMessages.size} failed messages." }

            failedMessages.forEach { failedMessage ->
                try {
                    // Parse envelope from bytes
                    val envelope = Envelope.parseFrom(failedMessage.messageEnvelopBytes)

                    // Process the message
                    val result = envelopToMessageProcessor.process(envelope, "FailedMessageProcessor")

                    if (result != null) {
                        // If processing succeeds, save the message and delete from failed messages
                        messageStore.putWhenNonExist(result.message)
                        wcdb.failedMessage.deleteObjects(DBFailedMessageModel.timestamp.eq(failedMessage.timestamp))
                    }
                } catch (e: Exception) {
                    L.e { "[FailedMessageProcessor] Failed to process message: ${e.stackTraceToString()}" }
                }
            }
        } catch (e: Exception) {
            L.e { "[FailedMessageProcessor] Error processing failed messages: ${e.stackTraceToString()}" }
        }
    }
} 