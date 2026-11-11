package com.difft.android.chat.messages

import android.content.Context
import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.monitor.BackgroundPowerModel
import com.difft.android.base.monitor.BackgroundPowerSampler
import com.difft.android.base.utils.ChunkingMethod
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.chunked
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.websocket.api.AppWebSocketHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.difft.app.database.models.DBFailedMessageModel
import org.difft.app.database.wcdb
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import javax.inject.Inject
import javax.inject.Singleton


@Suppress("BlockingWcdbInSuspend")
@Singleton
class IncomingEnvelopMessageProcessor @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    private val webSocket: AppWebSocketHelper,
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
    private val asyncMessageJobsManager: AsyncMessageJobsManager,
    private val pendingMessageProcessor: PendingMessageProcessor,
    private val failedMessageProcessor: FailedMessageProcessor,
    private val messageNotificationUtil: MessageNotificationUtil,
    private val wakeLock: IncomingMessageWakeLock,
    private val powerSampler: BackgroundPowerSampler,
    private val messagingGate: MessagingGate,
) {
    
    internal data class QueuedEnvelope(val envelope: Envelope, val requestId: Long, val queuedAt: Long, val seq: Long)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _incomingMessagesFlow =
        MutableSharedFlow<QueuedEnvelope>(extraBufferCapacity = 30).apply {
            this
                .chunked(ChunkingMethod.ByTime(500, 30))
                .onEach { batch -> processBatch(batch) }
                .launchIn(appScope)
        }

    
    private suspend fun processBatch(batch: List<QueuedEnvelope>) {
        try {
            doProcessBatch(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            
            L.e { "[Message] batch processing crashed (collector preserved): ${e.stackTraceToString()}" }
        } finally {
            wakeLock.onProcessed(batch.maxOf { it.seq })
        }
    }

    



    internal suspend fun doProcessBatch(batch: List<QueuedEnvelope>) {
        wakeLock.onBatchStart()
        val admission = messagingGate.tryAdmit(AdmissionKind.RECEIVE, "ws")
        if (admission == null) {
            L.i { "[MigrationPause] event=receive_dropped source=ws count=${batch.size}" }
            return
        }
        admission.use { processAdmittedBatch(batch, it) }
    }

    private suspend fun processAdmittedBatch(batch: List<QueuedEnvelope>, admission: MessagingTicket) {
        if (batch.size > 1) L.i { "[Message] processing batch size=${batch.size}" }
        
        val now = SystemClock.elapsedRealtime()
        batch.forEach { (envelop, requestId, queuedAt, _) ->
            val acked = sendAck(requestId, envelop.timestamp)
            val queuedMs = now - queuedAt
            L.i { "[Message] recv type=${envelop.type} ts=${envelop.timestamp} requestId=$requestId acked=$acked queuedMs=$queuedMs" }
            
            runCatching { powerSampler.onEvent(BackgroundPowerModel.queueWaitEvent(queuedMs)) }
        }

        val failedEnvelopes = mutableListOf<Envelope>()

        
        val sortedBatch = batch.sortedBy { it.envelope.systemShowTimestamp }

        sortedBatch.forEach { (envelope) ->
            when (val processRes = envelopToMessageProcessor.process(envelope, "message", admission)) {
                is EnvelopeProcessResult.Success -> {
                    val result = processRes.result
                    
                    runCatching {
                        wcdb.failedMessage.deleteObjects(
                            DBFailedMessageModel.timestamp.eq(envelope.timestamp)
                        )
                    }.onFailure { e ->
                        L.w { "[Message] dedup deleteObjects failed ts=${envelope.timestamp}: ${e.stackTraceToString()}" }
                    }
                    if (result?.shouldShowNotification == true) {
                        appScope.launch {
                            messageNotificationUtil.showNotificationSuspend(
                                context = context,
                                message = result.message,
                                forWhat = result.conversation,
                            )
                        }
                    }
                }
                is EnvelopeProcessResult.PermanentFailure -> {
                    reportPermanentDrop(
                        processRes.reason,
                        processRes.cause,
                        envelope.timestamp,
                        tag = "message",
                    )
                }
                is EnvelopeProcessResult.TransientFailure -> {
                    failedEnvelopes.add(envelope)
                }
                
                EnvelopeProcessResult.Paused -> Unit
            }
        }

        
        if (failedEnvelopes.isNotEmpty()) {
            L.w { "[Message] ${failedEnvelopes.size} messages failed transient, saving for retry" }
            failedMessageProcessor.saveTransient(failedEnvelopes)
        }

        asyncMessageJobsManager.runAsyncJobs()
        pendingMessageProcessor.triggerProcess()
        failedMessageProcessor.triggerProcess()
    }

    suspend fun inComeMessage(envelope: Envelope, requestId: Long) {
        L.d { "[Message] envelope into flow type=${envelope.type} ts=${envelope.timestamp} requestId=$requestId" }
        val seq = wakeLock.onQueued()
        _incomingMessagesFlow.emit(QueuedEnvelope(envelope, requestId, SystemClock.elapsedRealtime(), seq))
    }

    
    private fun sendAck(requestId: Long, timestamp: Long): Boolean =
        kotlin.runCatching { webSocket.sendAckToChatDataWebSocketWithoutLog(requestId) }.onFailure {
            L.e { "[Message] sendAck error, requestId:$requestId timestamp:$timestamp exception:${it.stackTraceToString()}" }
        }.isSuccess
}
