package com.difft.android.chat.messages

import android.content.Context
import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
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

// All wcdb calls in this class run on Dispatchers.IO.
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
) {
    /** [queuedAt] is `elapsedRealtime`, so the wait includes time the CPU spent suspended. */
    private data class QueuedEnvelope(val envelope: Envelope, val requestId: Long, val queuedAt: Long, val seq: Long)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _incomingMessagesFlow =
        MutableSharedFlow<QueuedEnvelope>(extraBufferCapacity = 30).apply {
            this
                .chunked(ChunkingMethod.ByTime(500, 30))
                .onEach { batch -> processBatch(batch) }
                .launchIn(appScope)
        }

    /**
     * Process one batch under a top-level safety net so that an unexpected
     * throw (DB corruption, NPE in an inner helper, etc.) does NOT kill the
     * SharedFlow collector. The collector is the only consumer of incoming
     * messages: if it dies, the buffer fills (cap 30) and subsequent
     * messages stop being ACK'd — server eventually pauses pushing and the
     * pipeline is blocked. We accept losing one batch's processing rather
     * than risking the whole channel.
     *
     * `CancellationException` is rethrown — coroutine cancellation (logout
     * / scope shutdown) must propagate so the scope actually shuts down.
     */
    private suspend fun processBatch(batch: List<QueuedEnvelope>) {
        try {
            doProcessBatch(batch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Includes Error (OOM/SOF): logging + continue is safer than
            // killing the collector. If the process is truly out of memory
            // the next allocation will OOM again and Android's LMK will
            // recover; we just don't compound that with a dead pipeline.
            L.e { "[Message] batch processing crashed (collector preserved): ${e.stackTraceToString()}" }
        } finally {
            wakeLock.onProcessed(batch.maxOf { it.seq })
        }
    }

    private suspend fun doProcessBatch(batch: List<QueuedEnvelope>) {
        wakeLock.onBatchStart()
        if (batch.size > 1) L.i { "[Message] processing batch size=${batch.size}" }
        // Eager ACK — see issue #754 design §1.5. ACK first so a late
        // processing failure can't block the server's push pipeline;
        // recovery for transient failures is owned by FailedMessageProcessor.
        val now = SystemClock.elapsedRealtime()
        batch.forEach { (envelop, requestId, queuedAt, _) ->
            val acked = sendAck(requestId, envelop.timestamp)
            val queuedMs = now - queuedAt
            L.i { "[Message] recv type=${envelop.type} ts=${envelop.timestamp} requestId=$requestId acked=$acked queuedMs=$queuedMs" }
            // Telemetry must never interrupt the ACK loop.
            runCatching { powerSampler.onEvent(BackgroundPowerModel.queueWaitEvent(queuedMs)) }
        }

        val failedEnvelopes = mutableListOf<Envelope>()

        // Sort batch by systemShowTimestamp to ensure chronological order.
        val sortedBatch = batch.sortedBy { it.envelope.systemShowTimestamp }

        sortedBatch.forEach { (envelope) ->
            when (val processRes = envelopToMessageProcessor.process(envelope, "message")) {
                is EnvelopeProcessResult.Success -> {
                    val result = processRes.result
                    // G-3: WebSocket re-deliver dedup. If a previous attempt
                    // failed transiently for this ts, the retry queue still
                    // has a row — purge it now that we've succeeded.
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
            }
        }

        // Save failed messages to database for retry (Mutex-serialized UPSERT).
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

    /** @return whether the ack was written to the socket; the failure is logged here. */
    private fun sendAck(requestId: Long, timestamp: Long): Boolean =
        kotlin.runCatching { webSocket.sendAckToChatDataWebSocketWithoutLog(requestId) }.onFailure {
            L.e { "[Message] sendAck error, requestId:$requestId timestamp:$timestamp exception:${it.stackTraceToString()}" }
        }.isSuccess
}
