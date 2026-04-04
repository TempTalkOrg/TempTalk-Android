package org.thoughtcrime.securesms.messages

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ChunkingMethod
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.chunked
import org.difft.app.database.wcdb
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.tencent.wcdb.base.WCDBException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.difft.app.database.models.FailedMessageModel
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import com.difft.android.websocket.api.AppWebSocketHelper
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncomingEnvelopMessageProcessor @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val dbMessageStore: DBMessageStore,
    private val webSocket: AppWebSocketHelper,
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
    private val asyncMessageJobsManager: AsyncMessageJobsManager,
    private val pendingMessageProcessor: PendingMessageProcessor,
    private val failedMessageProcessor: FailedMessageProcessor,
    private val messageNotificationUtil: MessageNotificationUtil
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val _incomingMessagesFlow =
        MutableSharedFlow<Pair<Envelope, Long>>(extraBufferCapacity = 30).apply {
            this
                .chunked(ChunkingMethod.ByTime(500, 30))
                .onEach { batch ->
                    L.i { "[Message] Processing batch of ${batch.size} messages" }
                    // Send ACK for messages first
                    batch.forEach { (envelop, requestId) -> sendAck(requestId, envelop.timestamp) }

                    val failedEnvelopes = mutableListOf<Envelope>()

                    // Sort batch by systemShowTimestamp to ensure chronological order
                    val sortedBatch = batch.sortedBy { it.first.systemShowTimestamp }

                    // Process messages sequentially
                    sortedBatch.forEach { (envelope, _) ->
                        try {
                            val result = envelopToMessageProcessor.process(envelope, "message")
                            if (result != null) {
                                dbMessageStore.putWhenNonExist(result.message)
                                if (result.shouldShowNotification) {
                                    appScope.launch {
                                        messageNotificationUtil.showNotificationSuspend(
                                            context = context,
                                            message = result.message,
                                            forWhat = result.conversation
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Exception reporting handled in EnvelopToMessageProcessor and DBMessageStore
                            L.e { "[Message] process message ${envelope.timestamp} failed -> ${e.stackTraceToString()}" }
                            failedEnvelopes.add(envelope)
                        }
                    }

                    // Save failed messages to database for retry
                    if (failedEnvelopes.isNotEmpty()) {
                        L.w { "[Message] ${failedEnvelopes.size} messages failed, saving to FailedMessage" }
                        try {
                            val messageModels = failedEnvelopes.map { envelope ->
                                FailedMessageModel().apply {
                                    this.timestamp = envelope.timestamp
                                    this.messageEnvelopBytes = envelope.toByteArray()
                                }
                            }
                            wcdb.failedMessage.insertOrReplaceObjects(messageModels)
                        } catch (e: WCDBException) {
                            L.e { "[Message] saveFailedMessage error: ${e.stackTraceToString()}" }
                        }
                    }

                    asyncMessageJobsManager.runAsyncJobs()
                    pendingMessageProcessor.triggerProcess()
                    failedMessageProcessor.triggerProcess()
                }
                .launchIn(appScope)
        }

    suspend fun inComeMessage(envelope: Envelope, requestId: Long) {
        L.i {
            "[Message] NewInComingMessageProcessor receive new message into flow, type -> ${envelope.type} ${envelope.timestamp}"
        }
        _incomingMessagesFlow.emit(Pair(envelope, requestId))
    }

    private fun sendAck(requestId: Long, timestamp: Long) {
        kotlin.runCatching { webSocket.sendAckToChatDataWebSocketWithoutLog(requestId) }.onFailure {
            L.e { "[Message] sendAck error, requestId:$requestId timestamp:$timestamp exception:${it.stackTraceToString()}" }
        }.onSuccess {
            L.i { "[Message] sendAck success, requestId:$requestId timestamp:$timestamp" }
        }
    }
}