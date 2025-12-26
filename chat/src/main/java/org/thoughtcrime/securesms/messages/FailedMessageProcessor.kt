package org.thoughtcrime.securesms.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.sampleAfterFirst
import difft.android.messageserialization.MessageStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBFailedMessageModel
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(FlowPreview::class)
@Singleton
class FailedMessageProcessor @Inject constructor(
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
    private val messageStore: MessageStore,
    private val wcdb: WCDB,
) {
    companion object {
        private const val CLEANUP_DAYS_THRESHOLD = 10L
    }

    private val processEvents = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var hasCleanedUp = false

    /**
     * This function will auto call when this class's instance is created by Hilt.
     */
    @Inject
    fun initWhenInject() {
        processEvents.sampleAfterFirst(3000).onEach {
            processFailedMessagesInternal()
        }.launchIn(appScope)
    }

    fun triggerProcess() {
        processEvents.tryEmit(Unit)
    }

    private suspend fun processFailedMessagesInternal() {
        try {
            val failedMessages = wcdb.failedMessage.allObjects

            if (failedMessages.isEmpty()) {
                L.i { "[FailedMessageProcessor] No failed messages to process." }
                return
            }

            L.i { "[FailedMessageProcessor] Processing ${failedMessages.size} failed messages." }

            val processedTimestamps = mutableListOf<Long>()

            failedMessages.forEach { failedMessage ->
                try {
                    val envelope = Envelope.parseFrom(failedMessage.messageEnvelopBytes)
                    val result = envelopToMessageProcessor.process(envelope, "FailedMessageProcessor")

                    if (result != null) {
                        messageStore.putWhenNonExist(result.message)
                        processedTimestamps.add(failedMessage.timestamp)
                    }
                } catch (e: Exception) {
                    L.e { "[FailedMessageProcessor] Failed to process message ${failedMessage.timestamp}: ${e.stackTraceToString()}" }
                }
            }

            // Batch delete processed messages
            if (processedTimestamps.isNotEmpty()) {
                wcdb.failedMessage.deleteObjects(
                    DBFailedMessageModel.timestamp.`in`(processedTimestamps)
                )
                L.i { "[FailedMessageProcessor] Deleted ${processedTimestamps.size} processed failed messages." }
            }

            // Delete failed messages older than threshold (dirty data cleanup) - only once per launch cycle
            if (!hasCleanedUp) {
                val cleanupThreshold = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(CLEANUP_DAYS_THRESHOLD)
                val oldMessages = failedMessages.filter { it.timestamp < cleanupThreshold }

                if (oldMessages.isNotEmpty()) {
                    L.i { "[FailedMessageProcessor] Deleting ${oldMessages.size} old failed messages" }
                    wcdb.failedMessage.deleteObjects(
                        DBFailedMessageModel.timestamp.`in`(oldMessages.map { it.timestamp })
                    )
                }
                hasCleanedUp = true
            }
        } catch (e: Exception) {
            L.e { "[FailedMessageProcessor] Error processing failed messages: ${e.stackTraceToString()}" }
        }
    }
} 