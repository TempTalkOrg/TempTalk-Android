package com.difft.android.chat.jobs

import com.difft.android.base.log.lumberjack.L
import com.difft.android.websocket.api.push.exceptions.NoValidRecipientKeysException
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.ProofRequiredException
import com.difft.android.websocket.api.push.exceptions.ServerRejectedException
import com.difft.android.chat.jobmanager.impl.BackoffUtil
import java.io.IOException
import java.util.concurrent.TimeUnit

abstract class PushSendJob(parameters: Parameters) : com.difft.android.chat.jobs.BaseJob(parameters) {

    final override suspend fun onRun() {
        L.i { "Starting message send attempt" }
        onPushSend()
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
        // issue #970 ②: target confirmed to have no valid keys (group invalid / account
        // deregistered / server confirms empty) = permanent, stop retrying.
        // NoValidRecipientKeysException is intentionally not an IOException subtype, so the
        // trailing `is IOException` branch does not catch it.
        if (exception is NoValidRecipientKeysException) {
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
