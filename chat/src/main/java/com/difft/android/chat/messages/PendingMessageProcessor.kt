package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.sampleAfterFirst
import com.difft.android.base.utils.time.ServerTimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.difft.app.database.PendingMessageStore
import org.difft.app.database.PendingRowKey
import org.difft.app.database.WCDB
import org.difft.app.database.WcdbPendingMessageStore
import org.difft.app.database.models.PendingMessageModelNew
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton







@Singleton
class PendingMessageProcessor internal constructor(
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
    private val store: PendingMessageStore,
    private val messagingGate: MessagingGate,
    private val nowMillis: () -> Long,
) {
    @Inject
    constructor(
        envelopToMessageProcessor: EnvelopToMessageProcessor,
        wcdb: WCDB,
        messagingGate: MessagingGate,
    ) : this(envelopToMessageProcessor, WcdbPendingMessageStore(wcdb), messagingGate, ServerTimeProvider::nowMillis)

    companion object {
        private const val CLEANUP_DAYS_THRESHOLD = 10L

        



        fun isExpired(originalMessageTimeStamp: Long, nowMs: Long): Boolean =
            originalMessageTimeStamp < nowMs - TimeUnit.DAYS.toMillis(CLEANUP_DAYS_THRESHOLD)

        
        fun isParseable(envelopeBytes: ByteArray): Boolean =
            try {
                Envelope.parseFrom(envelopeBytes)
                true
            } catch (e: Exception) {
                false
            }

        
        fun isConsumed(result: EnvelopeProcessResult): Boolean = when (result) {
            is EnvelopeProcessResult.Success, is EnvelopeProcessResult.PermanentFailure -> true
            is EnvelopeProcessResult.TransientFailure, EnvelopeProcessResult.Paused -> false
        }
    }

    private val processEvents = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var hasCleanedUp = false

    


    @Inject
    fun initWhenInject() {
        processEvents.sampleAfterFirst(3000).onEach {
            
            
            
            try {
                runAdmittedTick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                L.e { "[PendingMessageProcessor] tick crashed (collector preserved): ${e.stackTraceToString()}" }
            }
        }.launchIn(appScope)
    }

    
    internal suspend fun runAdmittedTick() {
        val admission = messagingGate.tryAdmit(AdmissionKind.RECEIVE, "pending-drain")
        if (admission == null) {
            L.i { "[MigrationPause] event=receive_dropped source=pending-drain count=0" }
            return
        }
        admission.use { runTick(it) }
    }

    





    suspend fun drainAsOwner(owner: String): Int? {
        val admission = messagingGate.tryAdmitAsOwner(owner, AdmissionKind.RECEIVE, "pending-drain-owner") ?: return null
        return admission.use {
            runTick(it)
            store.count()
        }
    }

    private suspend fun runTick(admission: MessagingTicket) {
        val pendingMessages = store.loadAll()

        L.i { "[PendingMessageProcessor] pendingMessages:${pendingMessages.size}" }

        if (pendingMessages.isEmpty()) {
            return
        }

        
        val existingTargets = store.existingTargetTimestamps(pendingMessages.map { it.originalMessageTimeStamp })
        val consumed = pendingMessages
            .filter { it.originalMessageTimeStamp in existingTargets }
            .filter { process(it, admission) }

        
        
        
        if (consumed.isNotEmpty()) {
            val keys = consumed.mapNotNull { row -> row.messageId?.let { PendingRowKey(it, row.originalMessageTimeStamp) } }
            if (keys.size < consumed.size) {
                L.w { "[PendingMessageProcessor] consumed rows without messageId count=${consumed.size - keys.size}" }
            }
            store.deleteConsumed(keys)
        }

        
        
        if (!hasCleanedUp) {
            val now = nowMillis()
            val expiredTimestamps = pendingMessages.map { it.originalMessageTimeStamp }.filter { isExpired(it, now) }
            if (expiredTimestamps.isNotEmpty()) {
                L.i { "[PendingMessageProcessor] Deleting ${expiredTimestamps.size} old pending messages" }
                store.deleteByTargetTimestamps(expiredTimestamps)
            }
            hasCleanedUp = true
        }
    }

    






    private suspend fun process(pendingMessage: PendingMessageModelNew, admission: MessagingTicket): Boolean {
        val originalTs = pendingMessage.originalMessageTimeStamp
        val envelope = try {
            Envelope.parseFrom(pendingMessage.messageEnvelopBytes)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            
            L.e { "[PendingMessageProcessor] envelope parse failed originalTs=$originalTs, dropping row: ${e.stackTraceToString()}" }
            reportPermanentDrop(DropReason.DECRYPTION_DATA_CORRUPT, e, originalTs, tag = "pending-timestamp")
            return true
        }
        val result = envelopToMessageProcessor.process(envelope, "pending-timestamp", admission)
        when (result) {
            
            is EnvelopeProcessResult.PermanentFailure ->
                reportPermanentDrop(result.reason, result.cause, envelope.timestamp, tag = "pending-timestamp")
            is EnvelopeProcessResult.TransientFailure ->
                L.w { "[PendingMessageProcessor] transient envelope.ts=${envelope.timestamp} originalTs=$originalTs, keeping row for retry: ${result.cause.stackTraceToString()}" }
            is EnvelopeProcessResult.Success, EnvelopeProcessResult.Paused -> Unit
        }
        return isConsumed(result)
    }

    fun triggerProcess() {
        processEvents.tryEmit(Unit)
    }
}
