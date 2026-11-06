package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rows left in each local retry queue after a [MigrationRetryQueueDrain] pass; null when that queue's
 * drain did not run or failed, so its count is unknown.
 */
data class RetryQueueResidual(val failed: Int?, val pending: Int?)

/**
 * The migration export's pre-snapshot pass over the local retry queues (#1382). Their rows were
 * consumed from the server before the pause, so they must reach the new device: `failed_message`
 * rows that still fail stop the export (the caller decides from [RetryQueueResidual.failed]), and
 * `pending_message_new` rows travel in the container as pending envelopes.
 */
@Singleton
class MigrationRetryQueueDrain @Inject constructor(
    private val pendingMessageProcessor: PendingMessageProcessor,
    private val failedMessageProcessor: FailedMessageProcessor,
) {

    /** Runs both drains once as [owner]'s pause; a failing drain is logged and reported as an unknown count. */
    suspend fun drainAsOwner(owner: String): RetryQueueResidual {
        val pending = drainSafely("pending") { pendingMessageProcessor.drainAsOwner(owner) }
        val failed = drainSafely("failed") { failedMessageProcessor.drainAsOwner(owner) }
        L.i { "[MigrationPause] event=retry_queue_residual failed=${failed ?: "skipped"} pending=${pending ?: "skipped"}" }
        return RetryQueueResidual(failed = failed, pending = pending)
    }

    private suspend fun drainSafely(queue: String, drain: suspend () -> Int?): Int? = try {
        drain()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        L.e { "[MigrationPause] event=retry_queue_drain_failed queue=$queue ${e.stackTraceToString()}" }
        null
    }
}
