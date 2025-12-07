package org.thoughtcrime.securesms.messages

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ChunkingMethod
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.chunked
import org.difft.app.database.wcdb
import difft.android.messageserialization.MessageStore
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tencent.wcdb.base.WCDBException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlin.system.measureTimeMillis

@Singleton
class IncomingEnvelopMessageProcessor @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val messageStore: MessageStore,
    private val webSocket: AppWebSocketHelper,
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
    private val asyncMessageJobsManager: AsyncMessageJobsManager,
    private val pendingMessageProcessor: PendingMessageProcessor,
    private val messageNotificationUtil: MessageNotificationUtil
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val _incomingMessagesFlow =
        MutableSharedFlow<Pair<Envelope, Long>>(extraBufferCapacity = 30).apply {
            this
                .chunked(ChunkingMethod.ByTime(500, 30))
                .onEach { batch ->
                    L.i { "[Message] Processing batch of ${batch.size} messages, timestamps: ${batch.map { it.first.timestamp }}" }
                    //sendAck for messages
                    batch.forEach { (envelop, requestId) -> sendAck(requestId, envelop.timestamp) }

                    val executedTime = measureTimeMillis {
                        try {
                            val results = coroutineScope {
                                batch.map { (envelop, _) ->
                                    async {
                                        envelopToMessageProcessor.process(envelop, "message")
                                    }
                                }.awaitAll().filterNotNull()
                            }

                            if (results.isNotEmpty()) {
                                messageStore.putWhenNonExist(
                                    messages = results.map { it.message }.toTypedArray()
                                )
                                L.i { "[Message] put ${results.size} messages to db ${results.map { it.message.timeStamp }}" }
                            }

                            results.filter { it.shouldShowNotification }
                                .groupBy({ it.conversation }) { it.message }
                                .forEach { (conversation, messages) ->
                                    appScope.launch {
                                        messageNotificationUtil.showNotificationSuspend(
                                            context = context,
                                            message = messages.lastOrNull() ?: return@launch,
                                            forWhat = conversation
                                        )
                                    }
                                }
                        } catch (e: Exception) {
                            FirebaseCrashlytics.getInstance().recordException(Exception("handle message batch exception, size: ${batch.size}", e))
                            L.e { "[Message] handle message batch exception -> ${e.stackTraceToString()}" }

                            try {
                                val messageModels = batch.map {
                                    FailedMessageModel().apply {
                                        this.timestamp = it.first.timestamp
                                        this.messageEnvelopBytes = it.first.toByteArray()
                                    }
                                }
                                wcdb.failedMessage.insertOrReplaceObjects(messageModels)
                            } catch (e: WCDBException) {
                                L.e { "[Message] saveFailedMessage error: ${e.stackTraceToString()}" }
                            }
                        }
                    }
                    L.i { "[Message] handle message batch(${batch.size} messages) executed time: $executedTime ms" }
                    asyncMessageJobsManager.runAsyncJobs()
                    pendingMessageProcessor.triggerProcess()
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