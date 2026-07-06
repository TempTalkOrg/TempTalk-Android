package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.sampleAfterFirst
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.DBPendingMessageModelNew
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// All wcdb calls in this class run on Dispatchers.IO.
@Suppress("BlockingWcdbInSuspend")
@Singleton
class PendingMessageProcessor @Inject constructor(
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
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
            // Safety net (parity with IncomingEnvelopMessageProcessor.processBatch):
            // unforeseen throws inside the tick must NOT kill the collector, or the
            // pending-message replay path stops working until app restart.
            try {
                runTick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                L.e { "[PendingMessageProcessor] tick crashed (collector preserved): ${e.stackTraceToString()}" }
            }
        }.launchIn(appScope)
    }

    private suspend fun runTick() {
            // 一次性获取所有待处理消息
            val pendingMessages = wcdb.pendingMessageNew.allObjects

            L.i { "[PendingMessageProcessor] pendingMessages:${pendingMessages.size}" }

            if (pendingMessages.isEmpty()) {
                return
            }

            // 获取所有原始消息时间戳
            val originalMessageTimestamps = pendingMessages.map { it.originalMessageTimeStamp }.distinct()

            // 批量查询已存在的原始消息
            val existingMessages = wcdb.message.getAllObjects(
                DBMessageModel.timeStamp.`in`(originalMessageTimestamps)
            ).map { it.timeStamp }.toSet()

            // 只处理已存在原始消息的待处理消息
            val messagesToProcess = pendingMessages.filter { it.originalMessageTimeStamp in existingMessages }
            val processedTimestamps = mutableListOf<Long>()

            // 处理所有待处理消息，单条异常不影响其他消息处理。
            //
            // Issue #754: `envelopToMessageProcessor.process()` now returns a
            // sealed `EnvelopeProcessResult` instead of throwing. We branch on
            // it explicitly:
            //  - Success / PermanentFailure → consume the row (delete from
            //    `pending_message_new`)
            //  - TransientFailure → keep the row so a future trigger picks
            //    it up again. Note: unlike `FailedMessageProcessor`, this
            //    table has NO retryCount/backoff — it relies on the 10-day
            //    `CLEANUP_DAYS_THRESHOLD` TTL as the only escape hatch. A
            //    persistently-failing envelope will be re-processed on
            //    every trigger until the TTL sweeps it.
            //  - parseFrom throwing on stored bytes is treated as permanent
            //    (same bytes → same failure)
            messagesToProcess.forEach { pendingMessage ->
                val originalTs = pendingMessage.originalMessageTimeStamp
                val envelope = try {
                    Envelope.parseFrom(pendingMessage.messageEnvelopBytes)
                } catch (e: Exception) {
                    // Envelope bytes corrupt — no envelope to extract a
                    // sender timestamp from. Use the row key (originalTs).
                    L.e { "[PendingMessageProcessor] envelope parse failed originalTs=$originalTs, dropping row: ${e.stackTraceToString()}" }
                    reportPermanentDrop(
                        DropReason.DECRYPTION_DATA_CORRUPT,
                        e,
                        originalTs,
                        tag = "pending-timestamp",
                    )
                    processedTimestamps.add(originalTs)
                    return@forEach
                }
                // Use the envelope's own timestamp for Crashlytics reporting so
                // the event correlates with server-side push logs (matches
                // every other call site of `reportPermanentDrop`).
                // `originalTs` is the row key (the message this pending row
                // depends on) — only used here for the table delete.
                when (val res = envelopToMessageProcessor.process(envelope, "pending-timestamp")) {
                    is EnvelopeProcessResult.Success -> {
                        processedTimestamps.add(originalTs)
                    }
                    is EnvelopeProcessResult.PermanentFailure -> {
                        reportPermanentDrop(
                            res.reason,
                            res.cause,
                            envelope.timestamp,
                            tag = "pending-timestamp",
                        )
                        processedTimestamps.add(originalTs)
                    }
                    is EnvelopeProcessResult.TransientFailure -> {
                        // Keep the row: don't add to processedTimestamps so it
                        // survives the batch-delete below and gets re-tried
                        // on the next trigger. Bounded only by TTL.
                        L.w { "[PendingMessageProcessor] transient envelope.ts=${envelope.timestamp} originalTs=$originalTs, keeping row for retry: ${res.cause.stackTraceToString()}" }
                    }
                }
            }

            // 批量删除已处理的消息
            if (processedTimestamps.isNotEmpty()) {
                wcdb.pendingMessageNew.deleteObjects(
                    DBPendingMessageModelNew.originalMessageTimeStamp.`in`(processedTimestamps)
                )
            }

            // 删除超过指定天数的待处理消息（脏数据清理）- 每个启动周期只执行一次
            if (!hasCleanedUp) {
                val cleanupThreshold = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(CLEANUP_DAYS_THRESHOLD)
                val oldMessages = pendingMessages.filter { it.originalMessageTimeStamp < cleanupThreshold }

                if (oldMessages.isNotEmpty()) {
                    L.i { "[PendingMessageProcessor] Deleting ${oldMessages.size} old pending messages" }
                    wcdb.pendingMessageNew.deleteObjects(
                        DBPendingMessageModelNew.originalMessageTimeStamp.`in`(oldMessages.map { it.originalMessageTimeStamp })
                    )
                }
                hasCleanedUp = true
            }
    }

    fun triggerProcess() {
        processEvents.tryEmit(Unit)
    }
}