package com.difft.android.chat.jobs

import androidx.annotation.WorkerThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.JobLogger
import com.difft.android.chat.jobmanager.impl.BackoffUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

abstract class BaseJob(parameters: Parameters) : Job(parameters) {

    @WorkerThread
    override suspend fun run(): Result {
        return try {
            onRun()
            Result.success()
        } catch (e: CancellationException) {
            // Real cancellation of this coroutine propagates; a stray one (e.g. a leaked
            // TimeoutCancellationException) is a job error, not a reason to end the runner.
            currentCoroutineContext().ensureActive()
            handleException(e)
        } catch (e: Exception) {
            handleException(e)
        }
    }

    private fun handleException(e: Exception): Result {
        return if (onShouldRetry(e)) {
            L.i { JobLogger.format(this, "Encountered a retryable exception.") + e }
            Result.retry(getNextRunAttemptBackoff(runAttempt + 1, e))
        } else {
            L.w { JobLogger.format(this, "Encountered a failing exception.") + e }
            Result.failure()
        }
    }

    /**
     * Should return how long you'd like to wait until the next retry, given the attempt count and
     * exception that caused the retry. The attempt count is the number of attempts that have been
     * made already, so this value will be at least 1.
     *
     * There is a sane default implementation here that uses exponential backoff, but jobs can
     * override this behavior to define custom backoff behavior.
     */
    open fun getNextRunAttemptBackoff(pastAttemptCount: Int, exception: Exception): Long {
        return BackoffUtil.exponentialBackoff(pastAttemptCount, TimeUnit.SECONDS.toMillis(60))
    }

    @Throws(Exception::class)
    protected abstract suspend fun onRun()

    protected abstract fun onShouldRetry(e: Exception): Boolean

    protected fun log(tag: String, message: String) {
        L.i { JobLogger.format(this, message) }
    }

    protected fun log(tag: String, extra: String, message: String) {
        L.i { JobLogger.format(this, extra, message) }
    }

    protected fun warn(tag: String, message: String) {
        warn(tag, "", message, null)
    }

    protected fun warn(tag: String, extra: Any, message: String) {
        warn(tag, extra.toString(), message, null)
    }

    protected fun warn(tag: String, t: Throwable?) {
        warn(tag, "", "", t)
    }

    protected fun warn(tag: String, message: String, t: Throwable?) {
        warn(tag, "", message, t)
    }

    protected fun warn(tag: String, extra: String, message: String, t: Throwable?) {
        L.w { JobLogger.format(this, extra, message) + t }
    }

}
