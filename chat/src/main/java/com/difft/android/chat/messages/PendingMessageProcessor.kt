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

/**
 * Replays `pending_message_new` rows (a reaction, recall or receipt waiting for its target message)
 * once the target is stored. Each row is consumed on its own, by its opaque `messageId` (#1382).
 *
 * All store calls in this class run on Dispatchers.IO.
 */
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

        /**
         * A row expires [CLEANUP_DAYS_THRESHOLD] days after its target message's timestamp, never
         * after its own receive time. The migration export applies the same rule (#1382).
         */
        fun isExpired(originalMessageTimeStamp: Long, nowMs: Long): Boolean =
            originalMessageTimeStamp < nowMs - TimeUnit.DAYS.toMillis(CLEANUP_DAYS_THRESHOLD)

        /** Bytes that do not parse as an envelope are a permanent failure on every tick (see [runTick]). */
        fun isParseable(envelopeBytes: ByteArray): Boolean =
            try {
                Envelope.parseFrom(envelopeBytes)
                true
            } catch (e: Exception) {
                false
            }

        /** Success and PermanentFailure consume the row; TransientFailure and Paused keep it for a later tick. */
        fun isConsumed(result: EnvelopeProcessResult): Boolean = when (result) {
            is EnvelopeProcessResult.Success, is EnvelopeProcessResult.PermanentFailure -> true
            is EnvelopeProcessResult.TransientFailure, EnvelopeProcessResult.Paused -> false
        }
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
                runAdmittedTick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                L.e { "[PendingMessageProcessor] tick crashed (collector preserved): ${e.stackTraceToString()}" }
            }
        }.launchIn(appScope)
    }

    /** One tick under one receive ticket (#1382); skipped while messaging is closed. */
    internal suspend fun runAdmittedTick() {
        val admission = messagingGate.tryAdmit(AdmissionKind.RECEIVE, "pending-drain")
        if (admission == null) {
            L.i { "[MigrationPause] event=receive_dropped source=pending-drain count=0" }
            return
        }
        admission.use { runTick(it) }
    }

    /**
     * The migration export's pre-snapshot drain (#1382): one tick under [owner]'s pause, so rows
     * consumed from the server before the pause land in the snapshot.
     *
     * @return the rows left in the table, or null when [owner]'s export is not the one exporting.
     */
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

        // Only rows whose target message is already stored can apply now; the rest wait.
        val existingTargets = store.existingTargetTimestamps(pendingMessages.map { it.originalMessageTimeStamp })
        val consumed = pendingMessages
            .filter { it.originalMessageTimeStamp in existingTargets }
            .filter { process(it, admission) }

        // Exactly the consumed rows, by messageId plus the target they were loaded with: a sibling row
        // on the same target that hit a transient failure or the pause survives, and so does a row the
        // replay re-saved under the same messageId for another target it still waits on (#1382).
        if (consumed.isNotEmpty()) {
            val keys = consumed.mapNotNull { row -> row.messageId?.let { PendingRowKey(it, row.originalMessageTimeStamp) } }
            if (keys.size < consumed.size) {
                L.w { "[PendingMessageProcessor] consumed rows without messageId count=${consumed.size - keys.size}" }
            }
            store.deleteConsumed(keys)
        }

        // Dirty-data sweep, once per process. Expiry depends only on the target timestamp, so every
        // row on an expired timestamp is expired too.
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

    /**
     * Applies one row. Issue #754: `process()` returns a sealed result instead of throwing; this table
     * has no retryCount/backoff, so the 10-day TTL is a transient row's only escape hatch. Bytes that
     * do not parse are permanent (same bytes, same failure).
     *
     * @return true when the row is consumed and must be deleted.
     */
    private suspend fun process(pendingMessage: PendingMessageModelNew, admission: MessagingTicket): Boolean {
        val originalTs = pendingMessage.originalMessageTimeStamp
        val envelope = try {
            Envelope.parseFrom(pendingMessage.messageEnvelopBytes)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // No envelope to take a sender timestamp from; report the row's target timestamp.
            L.e { "[PendingMessageProcessor] envelope parse failed originalTs=$originalTs, dropping row: ${e.stackTraceToString()}" }
            reportPermanentDrop(DropReason.DECRYPTION_DATA_CORRUPT, e, originalTs, tag = "pending-timestamp")
            return true
        }
        val result = envelopToMessageProcessor.process(envelope, "pending-timestamp", admission)
        when (result) {
            // The envelope's own timestamp correlates with server-side push logs.
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
