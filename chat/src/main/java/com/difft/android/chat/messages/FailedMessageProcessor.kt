package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
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

/**
 * Drives the `failed_message` retry queue: serialized UPSERT on the way in,
 * bounded scheduled retries on the way out.
 *
 * Replaces the previous "insertOrReplace + retry forever every 3 s" loop that
 * accumulated dead letters and burned Crashlytics quota.
 */
// All wcdb calls in this class run on Dispatchers.IO.
@Suppress("BlockingWcdbInSuspend")
@OptIn(FlowPreview::class)
@Singleton
class FailedMessageProcessor @Inject constructor(
    private val envelopToMessageProcessor: EnvelopToMessageProcessor,
    private val wcdb: WCDB,
) {
    companion object {
        const val MAX_RETRIES = 5
        const val MAX_PER_TICK = 50

        /** Bounded TTL — 3 days. Normal MAX_RETRIES path resolves in ~35 minutes. */
        val TTL_MILLIS: Long = TimeUnit.DAYS.toMillis(3)

        /**
         * Minimum wall-clock wait after the Nth failure (index = retryCount).
         * `backoffMillis(0)` ≈ 1 s, `backoffMillis(4)` ≈ 30 min. Total accumulated
         * wall-clock ≈ 35 min 36 s before MAX_RETRIES gives up.
         */
        val BACKOFF_TABLE: LongArray = longArrayOf(
            TimeUnit.SECONDS.toMillis(1),     // after 1st failure
            TimeUnit.SECONDS.toMillis(5),     // after 2nd
            TimeUnit.SECONDS.toMillis(30),    // after 3rd
            TimeUnit.MINUTES.toMillis(5),     // after 4th
            TimeUnit.MINUTES.toMillis(30),    // after 5th
        )
    }

    private val processEvents = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Serializes [saveTransient] calls so two coroutines enqueuing the same
     * envelope timestamp don't both insert (primary-key conflict) or one of
     * them overwrite the other's retryCount with a freshly-zeroed one.
     */
    private val saveMutex = Mutex()

    /**
     * First-cycle TTL cleanup latch. The `sampleAfterFirst(3000)` Flow is
     * collected sequentially — each `onEach` lambda runs to completion
     * before the next emission is delivered — so concurrent access is not
     * possible. `appScope` is `Dispatchers.IO` (a thread pool) though, so
     * the read and write across ticks may land on different threads;
     * `@Volatile` guarantees the JVM publishes the write so a later tick
     * sees the latest value across that thread switch.
     *
     * Don't introduce a `withContext { ... }` inside the `onEach` lambda
     * that returns control before [hasCleanedUp] is set — that would
     * break the sequential-collection contract this relies on.
     */
    @Volatile
    private var hasCleanedUp = false

    /**
     * Auto-invoked by Hilt after construction.
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

    /**
     * Enqueue transient-failed envelopes for later retry. Uses [saveMutex] to
     * serialize concurrent callers so duplicate timestamps don't race and
     * silently drop retryCount progress.
     *
     * If a row already exists for a given envelope timestamp, only
     * `lastAttemptTime` is refreshed — `retryCount` progress is preserved.
     */
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

    private suspend fun processFailedMessagesInternal() {
        try {
            // (CancellationException is rethrown below — must propagate so
            // scope shutdown / logout actually cancels this tick.)
            // First-cycle TTL cleanup: prune anything whose last attempt is older
            // than TTL before we bother loading due rows. Keyed on `lastAttemptTime`
            // (not envelope `timestamp`) so server-side backlogs of old envelopes
            // still get a full retry window once we receive them — using
            // `timestamp` would zero-retry anything older than TTL on first sight.
            // Note: `lastAttemptTime = 0` legacy rows (pre-upgrade) satisfy
            // `0 < cutoff` and get swept too — desired, since their attempt
            // history is unknown and aligning them to the new schema is safer
            // than indefinitely keeping them.
            if (!hasCleanedUp) {
                val cutoff = System.currentTimeMillis() - TTL_MILLIS
                runCatching {
                    wcdb.failedMessage.deleteObjects(DBFailedMessageModel.lastAttemptTime.lt(cutoff))
                }.onFailure {
                    L.w { "[FailedMessageProcessor] TTL cleanup failed: ${it.stackTraceToString()}" }
                }
                hasCleanedUp = true
            }

            val due = loadDueMessages()
            if (due.isEmpty()) return

            L.i { "[FailedMessageProcessor] Processing ${due.size} due failed messages." }
            due.forEach { failed ->
                processOne(failed)
            }
        } catch (e: CancellationException) {
            // Must propagate so scope shutdown actually cancels (parity with
            // IncomingEnvelopMessageProcessor.processBatch + EnvelopToMessageProcessor.process).
            throw e
        } catch (e: Throwable) {
            L.e { "[FailedMessageProcessor] Error processing failed messages: ${e.stackTraceToString()}" }
        }
    }

    /**
     * One row's retry attempt. Outcomes:
     * - Success → delete the failed_message row (the actual message was already
     *   persisted into the message table inside [EnvelopToMessageProcessor.process]
     *   via `putWhenNonExist`).
     * - Permanent → report + delete the failed_message row.
     * - Transient → bump retryCount + lastAttemptTime; at MAX_RETRIES, give-up
     *   report + delete the failed_message row.
     */
    private suspend fun processOne(failed: FailedMessageModel) {
        val envelope = try {
            Envelope.parseFrom(failed.messageEnvelopBytes)
        } catch (e: Exception) {
            // Stored bytes corrupt — nothing to retry. Same input → same failure.
            deleteFailedRow(failed.timestamp)
            reportPermanentDrop(
                DropReason.DECRYPTION_DATA_CORRUPT,
                e,
                failed.timestamp,
                tag = "FailedMessageProcessor",
            )
            return
        }

        when (val res = envelopToMessageProcessor.process(envelope, "FailedMessageProcessor")) {
            is EnvelopeProcessResult.Success -> {
                deleteFailedRow(failed.timestamp)
            }
            is EnvelopeProcessResult.PermanentFailure -> {
                // Reclassified as permanent on a later attempt — report + purge.
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
        }
    }

    /**
     * Delete a single failed_message row with error logging.
     *
     * Silent swallow of a DELETE failure here is bad enough to log explicitly
     * (per logging-standards.md): if the row stays after the caller assumed
     * it was gone, future ticks re-process it, wasting CPU and producing
     * duplicate Crashlytics noise. TTL eventually cleans it up but the
     * intermediate loop is hard to diagnose without this log.
     */
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
            // Give-up-specific log first (carries retry attempt count); then
            // route through the shared [reportPermanentDrop] helper so the
            // generic L.w + Crashlytics record pair stays locked together
            // with every other permanent-drop site. Tagged `RETRY_EXHAUSTED`
            // for Crashlytics issue separation from other permanent drops.
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
                // Update failure (e.g. disk full) is dangerous: leaving the row
                // with the old retryCount + lastAttemptTime makes it due again
                // on the next tick, looping every ~1 s. Fall back to deleting
                // the row — TTL would clean it eventually but we don't want
                // to pin the retry loop in the meantime.
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

    /**
     * Load rows that are due for another attempt: `retryCount < MAX_RETRIES`,
     * ordered by oldest attempt first. Over-read a 2x window and filter by
     * backoff to keep query cost bounded while still finding old-enough rows.
     */
    private fun loadDueMessages(): List<FailedMessageModel> {
        val candidates = runCatching {
            wcdb.failedMessage.getAllObjects(
                DBFailedMessageModel.retryCount.lt(MAX_RETRIES),
                DBFailedMessageModel.lastAttemptTime.order(Order.Asc),
                (MAX_PER_TICK * 2).toLong()
            )
        }.getOrElse { e ->
            L.e { "[FailedMessageProcessor] loadDueMessages query failed: ${e.stackTraceToString()}" }
            return emptyList()
        }
        val now = System.currentTimeMillis()
        return candidates
            .filter { now - it.lastAttemptTime >= backoffMillis(it.retryCount) }
            .take(MAX_PER_TICK)
    }

    /**
     * Look up the base wait for [retryCount] in [BACKOFF_TABLE] (clamped) and
     * apply ±20% uniform jitter to avoid thundering-herd when many rows fail
     * at once and become due simultaneously.
     */
    internal fun backoffMillis(retryCount: Int): Long {
        val idx = retryCount.coerceIn(0, BACKOFF_TABLE.lastIndex)
        val base = BACKOFF_TABLE[idx]
        val jitter = base / 5L
        // nextLong(from, until) — until is exclusive; +1 makes the range symmetric.
        return base + Random.nextLong(-jitter, jitter + 1)
    }
}
