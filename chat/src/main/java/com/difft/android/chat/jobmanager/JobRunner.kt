package com.difft.android.chat.jobmanager

import android.app.Application
import android.os.PowerManager
import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.util.WakeLockUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * A coroutine-based runner that constantly checks for available [Job]s owned by the [JobController].
 * When one is available, this class will execute it and call the appropriate methods on
 * [JobController] based on the result.
 *
 * [JobRunner] and [JobController] were written such that you should be able to have
 * N concurrent [JobRunner]s operating over the same [JobController].
 */
internal class JobRunner(
    private val application: Application,
    private val id: Int,
    private val jobController: JobController,
    private val jobPredicate: JobPredicate,
    private val managementDispatcher: CoroutineDispatcher
) {

    fun launchIn(scope: CoroutineScope): kotlinx.coroutines.Job {
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                // Fail-soft: no wcdb handle here, so type-catch rather than flag-guard the DB
                // chokepoint. A cipher-key failure is process-lifetime dead — stop this runner,
                // jobs stay persisted. CancellationException isn't a subtype, so it still propagates.
                try {
                    val job = jobController.pullNextEligibleJobForExecution(jobPredicate, managementDispatcher)
                    val result = try {
                        runJob(job)
                    } finally {
                        withContext(managementDispatcher + NonCancellable) { jobController.onJobFinished(job) }
                    }

                    when {
                        result.isSuccess() -> {
                            withContext(managementDispatcher) { jobController.onSuccess(job) }
                        }
                        result.isRetry() -> {
                            withContext(managementDispatcher) {
                                jobController.onRetry(job, result.getBackoffInterval())
                            }
                            job.onRetry()
                        }
                        result.isFailure() -> {
                            withContext(managementDispatcher) { jobController.onFailure(job) }
                            job.onFailure()
                        }
                        else -> error("Invalid job result!")
                    }
                } catch (e: WCDBKeyUnavailableException) {
                    L.w { "[JobRunner] cipher key unavailable, stopping runner id=$id: ${e.message}" }
                    break
                }
            }
        }
    }

    private suspend fun runJob(job: Job): Job.Result {
        val runStartTime = System.currentTimeMillis()
        L.i { JobLogger.format(job, id.toString(), "Running job.") }

        if (isJobExpired(job)) {
            L.w { JobLogger.format(job, id.toString(), "Failing after surpassing its lifespan.") }
            return Job.Result.failure()
        }

        var result: Job.Result? = null
        var wakeLock: PowerManager.WakeLock? = null

        try {
            wakeLock = WakeLockUtil.acquire(application, PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TIMEOUT, job.id)
            result = job.run()

            if (job.isCanceled()) {
                L.w { JobLogger.format(job, id.toString(), "Failing because the job was canceled.") }
                result = Job.Result.failure()
            }
        } catch (e: CancellationException) {
            throw e // Must rethrow -- coroutine cancellation is not a job failure
        } catch (e: Exception) {
            L.w { JobLogger.format(job, id.toString(), "Failing due to an unexpected exception.") + e }
            return Job.Result.failure()
        } finally {
            wakeLock?.let { WakeLockUtil.release(it, job.id) }
        }

        printResult(job, result!!, runStartTime)

        if (result.isRetry() &&
            job.runAttempt + 1 >= job.parameters.maxAttempts &&
            job.parameters.maxAttempts != Job.Parameters.UNLIMITED
        ) {
            L.w { JobLogger.format(job, id.toString(), "Failing after surpassing its max number of attempts.") }
            return Job.Result.failure()
        }

        return result
    }

    private fun isJobExpired(job: Job): Boolean {
        if (job.parameters.lifespan == Job.Parameters.IMMORTAL) return false
        val expirationTime = (job.parameters.createTime + job.parameters.lifespan).takeIf { it >= 0 } ?: Long.MAX_VALUE
        return expirationTime <= System.currentTimeMillis()
    }

    private fun printResult(job: Job, result: Job.Result, runStartTime: Long) {
        when {
            result.getException() != null ->
                L.e { JobLogger.format(job, id.toString(), "Job failed with a fatal exception. Crash imminent.") }
            result.isFailure() ->
                L.w { JobLogger.format(job, id.toString(), "Job failed.") }
            else ->
                L.i { JobLogger.format(job, id.toString(), "Job finished with result $result in ${System.currentTimeMillis() - runStartTime} ms.") }
        }
    }

    companion object {
        private val WAKE_LOCK_TIMEOUT = TimeUnit.MINUTES.toMillis(10)
    }
}
