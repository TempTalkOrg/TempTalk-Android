package org.thoughtcrime.securesms.jobs

import com.difft.android.base.log.lumberjack.L
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.ProofRequiredException
import com.difft.android.websocket.api.push.exceptions.ServerRejectedException
import kotlinx.coroutines.runBlocking
import org.thoughtcrime.securesms.jobmanager.impl.BackoffUtil
import java.io.IOException
import java.util.concurrent.TimeUnit

abstract class PushSendJob(parameters: Parameters) : BaseJob(parameters) {

    final override fun onRun() {
        L.i { "Starting message send attempt" }
        runBlocking { onPushSend() }
        L.i { "Message send completed" }
    }

    override fun onRetry() {
        L.i { "onRetry()" }
        if (runAttempt > 1) {
            L.i { "Scheduling service outage detection job." }
        }
    }

    override fun onShouldRetry(exception: Exception): Boolean {
        if (exception is ServerRejectedException) {
            return false
        }
        return exception is IOException
    }

    override fun getNextRunAttemptBackoff(pastAttemptCount: Int, exception: Exception): Long {
        if (exception is ProofRequiredException) {
            val backoff = exception.retryAfterSeconds
            warn(TAG, "[Proof Required] Retry-After is $backoff seconds.")
            if (backoff >= 0) {
                return TimeUnit.SECONDS.toMillis(backoff)
            }
        } else if (exception is NonSuccessfulResponseCodeException) {
            if (exception.is5xx) {
                return BackoffUtil.exponentialBackoff(pastAttemptCount, 6000)
            }
        }
        return super.getNextRunAttemptBackoff(pastAttemptCount, exception)
    }

    protected abstract suspend fun onPushSend()

    companion object {
        private val TAG = L.tag(PushSendJob::class.java)
    }
}
