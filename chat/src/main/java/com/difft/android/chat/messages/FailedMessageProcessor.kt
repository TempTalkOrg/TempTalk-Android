package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.sampleAfterFirst
import com.tencent.wcdb.base.Value
import com.tencent.wcdb.winq.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBFailedMessageModel
import org.difft.app.database.models.FailedMessageModel
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random






@Suppress("BlockingWcdbInSuspend")
@OptIn(FlowPreview::class)
@Singleton
class FailedMessageProcessor @Inject constructor(
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
    private val wcdb: WCDB,
    private val messagingGate: MessagingGate,
) {
    companion object {
        const val MAX_RETRIES = 5
        const val MAX_PER_TICK = 50

        
        val TTL_MILLIS: Long = TimeUnit.DAYS.toMillis(3)

        




        val BACKOFF_TABLE: LongArray = longArrayOf(
            TimeUnit.SECONDS.toMillis(1),     
            TimeUnit.SECONDS.toMillis(5),     
            TimeUnit.SECONDS.toMillis(30),    
            TimeUnit.MINUTES.toMillis(5),     
            TimeUnit.MINUTES.toMillis(30),    
        )
    }

    private val processEvents = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    




    private val saveMutex = Mutex()

    








    @Volatile
    private var hasCleanedUp = false

    


    @Inject
    fun initWhenInject() {
        processEvents.sampleAfterFirst(3000).onEach {
            processAdmittedTick()
        }.launchIn(appScope)
    }

    fun triggerProcess() {
        processEvents.tryEmit(Unit)
    }

    







    suspend fun saveTransient(envelopes: List<Envelope>) {
        if (envelopes.isEmpty()) return
        saveMutex.withLock {
            val now = System.currentTimeMillis()
            envelopes.forEach { envelope ->
                try {
                    val existing = wcdb.failedMessage.getFirstObject(
                        DBFailedMessageModel.timestamp.eq(envelope.timestamp)
                    )
                    if (existing == null) {
                        wcdb.failedMessage.insertObject(FailedMessageModel().apply {
                            this.timestamp = envelope.timestamp
                            this.messageEnvelopBytes = envelope.toByteArray()
                            this.retryCount = 0
                            this.lastAttemptTime = now
                        })
                    } else {
                        wcdb.failedMessage.updateValue(
                            Value(now),
                            DBFailedMessageModel.lastAttemptTime,
                            DBFailedMessageModel.timestamp.eq(envelope.timestamp)
                        )
                    }
                } catch (e: Exception) {
                    L.e { "[FailedMessageProcessor] saveTransient failed ts=${envelope.timestamp}: ${e.stackTraceToString()}" }
                }
            }
        }
    }

    
    internal suspend fun processAdmittedTick() {
        val admission = messagingGate.tryAdmit(AdmissionKind.RECEIVE, "failed-drain")
        if (admission == null) {
            L.i { "[MigrationPause] event=receive_dropped source=failed-drain count=0" }
            return
        }
        admission.use { processFailedMessagesInternal(it) }
    }

    






    suspend fun drainAsOwner(owner: String): Int? {
        val admission = messagingGate.tryAdmitAsOwner(owner, AdmissionKind.RECEIVE, "failed-drain-owner") ?: return null
        return admission.use {
            processFailedMessagesInternal(it, ignoreBackoff = true)
            wcdb.failedMessage.allObjects.size
        }
    }

    private suspend fun processFailedMessagesInternal(admission: MessagingTicket, ignoreBackoff: Boolean = false) {
        try {
            
            if (!hasCleanedUp) {
                val cutoff = System.currentTimeMillis() - TTL_MILLIS
                runCatching {
                    wcdb.failedMessage.deleteObjects(DBFailedMessageModel.lastAttemptTime.lt(cutoff))
                }.onFailure {
                    L.w { "[FailedMessageProcessor] TTL cleanup failed: ${it.stackTraceToString()}" }
                }
                hasCleanedUp = true
            }

            val due = loadDueMessages(ignoreBackoff)
            if (due.isEmpty()) return

            L.i { "[FailedMessageProcessor] Processing ${due.size} due failed messages." }
            due.forEach { failed ->
                processOne(failed, admission)
            }
        } catch (e: CancellationException) {
            
            
            throw e
        } catch (e: Throwable) {
            L.e { "[FailedMessageProcessor] Error processing failed messages: ${e.stackTraceToString()}" }
        }
    }

    








    private suspend fun processOne(failed: FailedMessageModel, admission: MessagingTicket) {
        val envelope = try {
            Envelope.parseFrom(failed.messageEnvelopBytes)
        } catch (e: Exception) {
            
            deleteFailedRow(failed.timestamp)
            reportPermanentDrop(
                DropReason.DECRYPTION_DATA_CORRUPT,
                e,
                failed.timestamp,
                tag = "FailedMessageProcessor",
            )
            return
        }

        when (val res = envelopToMessageProcessor.process(envelope, "FailedMessageProcessor", admission)) {
            is EnvelopeProcessResult.Success -> {
                deleteFailedRow(failed.timestamp)
            }
            is EnvelopeProcessResult.PermanentFailure -> {
                
                reportPermanentDrop(
                    res.reason,
                    res.cause,
                    failed.timestamp,
                    tag = "FailedMessageProcessor",
                )
                deleteFailedRow(failed.timestamp)
            }
            is EnvelopeProcessResult.TransientFailure -> {
                bumpRetryOrGiveUp(failed, res.cause)
            }
            
            EnvelopeProcessResult.Paused -> Unit
        }
    }

    
    private fun deleteFailedRow(timestamp: Long) {
        runCatching {
            wcdb.failedMessage.deleteObjects(DBFailedMessageModel.timestamp.eq(timestamp))
        }.onFailure { e ->
            L.e { "[FailedMessageProcessor] deleteObjects failed ts=$timestamp: ${e.stackTraceToString()}" }
        }
    }

    private fun bumpRetryOrGiveUp(failed: FailedMessageModel, cause: Throwable) {
        val newCount = failed.retryCount + 1
        if (newCount >= MAX_RETRIES) {
            
            L.w {
                "[FailedMessageProcessor] giving up ts=${failed.timestamp} after $newCount attempts " +
                    "err=${cause.javaClass.simpleName}"
            }
            reportPermanentDrop(
                DropReason.RETRY_EXHAUSTED,
                cause,
                failed.timestamp,
                tag = "FailedMessageProcessor",
            )
            deleteFailedRow(failed.timestamp)
        } else {
            runCatching {
                wcdb.failedMessage.updateRow(
                    arrayOf(Value(newCount.toLong()), Value(System.currentTimeMillis())),
                    arrayOf(DBFailedMessageModel.retryCount, DBFailedMessageModel.lastAttemptTime),
                    DBFailedMessageModel.timestamp.eq(failed.timestamp)
                )
            }.onFailure { e ->
                
                L.e {
                    "[FailedMessageProcessor] updateRow failed ts=${failed.timestamp}, " +
                        "deleting row to avoid retry loop: ${e.stackTraceToString()}"
                }
                runCatching {
                    wcdb.failedMessage.deleteObjects(DBFailedMessageModel.timestamp.eq(failed.timestamp))
                }.onFailure { ee ->
                    L.e {
                        "[FailedMessageProcessor] fallback delete also failed ts=${failed.timestamp}: " +
                            ee.stackTraceToString()
                    }
                }
            }
        }
    }

    




    private fun loadDueMessages(ignoreBackoff: Boolean = false): List<FailedMessageModel> {
        val candidates = runCatching {
            if (ignoreBackoff) {
                wcdb.failedMessage.getAllObjects(
                    DBFailedMessageModel.retryCount.lt(MAX_RETRIES),
                    DBFailedMessageModel.lastAttemptTime.order(Order.Asc),
                )
            } else {
                wcdb.failedMessage.getAllObjects(
                    DBFailedMessageModel.retryCount.lt(MAX_RETRIES),
                    DBFailedMessageModel.lastAttemptTime.order(Order.Asc),
                    (MAX_PER_TICK * 2).toLong()
                )
            }
        }.getOrElse { e ->
            L.e { "[FailedMessageProcessor] loadDueMessages query failed: ${e.stackTraceToString()}" }
            return emptyList()
        }
        if (ignoreBackoff) return candidates
        val now = System.currentTimeMillis()
        return candidates
            .filter { now - it.lastAttemptTime >= backoffMillis(it.retryCount) }
            .take(MAX_PER_TICK)
    }

    




    internal fun backoffMillis(retryCount: Int): Long {
        val idx = retryCount.coerceIn(0, BACKOFF_TABLE.lastIndex)
        val base = BACKOFF_TABLE[idx]
        val jitter = base / 5L
        
        return base + Random.nextLong(-jitter, jitter + 1)
    }
}
