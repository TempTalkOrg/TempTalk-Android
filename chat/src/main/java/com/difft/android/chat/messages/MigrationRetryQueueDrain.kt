package com.difft.android.chat.messages

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton





data class RetryQueueResidual(val failed: Int?, val pending: Int?)







@Singleton
class MigrationRetryQueueDrain @Inject constructor(
    private val pendingMessageProcessor: PendingMessageProcessor,
    private val failedMessageProcessor: FailedMessageProcessor,
) {

    
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
