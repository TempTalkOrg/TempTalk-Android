package com.difft.android.chat.jobmanager

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.video.exo.SimpleExoPlayerPool
import io.mockk.every
import io.mockk.mockk
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Fixtures shared by the `JobSchedulerScheduler` test files, which are split only to stay under
 * the project's file-size limit and would otherwise duplicate all of this.
 */

/** Upper bound for any wait on work the scheduler hands to its own executor. */
internal const val AWAIT_SECONDS = 5L

/** The id a constraint-less job is persisted under: `"".hashCode()`. */
internal const val PLAIN_JOB_ID = 0

internal val NETWORK_JOB_ID = "NETWORK".hashCode()

/**
 * Takes [count] entries in arrival order, failing the test rather than hanging if the scheduler
 * never produced them. [label] names the entry kind in that failure.
 */
internal fun <T> LinkedBlockingQueue<T>.drainInOrder(count: Int, label: String): List<T> = List(count) {
    poll(AWAIT_SECONDS, TimeUnit.SECONDS)
        ?: error("timed out waiting for $label ${it + 1} of $count")
}

/**
 * Runs [block] with the default uncaught-exception handler capturing into the queue it is given,
 * restoring the previous handler afterwards. The scheduler's executor reports an escaping
 * throwable to its worker thread's uncaught handler rather than to the caller, so that queue
 * staying empty is what proves a failure was contained.
 */
internal fun withUncaughtExceptions(block: (LinkedBlockingQueue<Throwable>) -> Unit) {
    val uncaught = LinkedBlockingQueue<Throwable>()
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
    try {
        block(uncaught)
    } finally {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }
}

/**
 * Records every `jobFinished` call with its jobId, which the stock `ShadowJobService` cannot do
 * (it keeps a single instance-wide flag) — and per-run completion is exactly what the rows using
 * this exist to pin.
 */
@Implements(JobService::class)
open class RecordingJobServiceShadow : ShadowService() {

    @Implementation
    protected fun jobFinished(params: JobParameters, needsReschedule: Boolean) {
        onFinish?.invoke()
        if (throwOnFinish) {
            // Recorded before the throw so a row exercising the failure still has an event to
            // synchronise on — nothing follows the completion any more.
            events.add("finish-failed:${params.jobId}")
            throw IllegalStateException("Job already finished")
        }
        events.add("finished:${params.jobId}:$needsReschedule")
    }

    companion object {
        val events: LinkedBlockingQueue<String> = LinkedBlockingQueue()

        @Volatile
        var throwOnFinish: Boolean = false

        /** Lets a row record the completion's position among other steps it observes. */
        @Volatile
        var onFinish: (() -> Unit)? = null

        fun reset() {
            events.clear()
            throwOnFinish = false
            onFinish = null
        }
    }
}

/**
 * [ApplicationDependencies] caches the manager it hands out, so the mock has to outlive a single
 * test instance.
 */
internal val sharedJobManager: JobManager = mockk(relaxed = true)

internal object TestDependencyProvider : ApplicationDependencies.Provider {
    /** Set to make the dependency lookup fail the way a real provider failure would. */
    @Volatile
    var failure: RuntimeException? = null

    override fun provideJobManager(): JobManager = failure?.let { throw it } ?: sharedJobManager

    override fun provideExoPlayerPool(): SimpleExoPlayerPool =
        throw UnsupportedOperationException("not used by these tests")
}

/** Builds relaxed [JobParameters] reporting [jobId]; distinct instances model distinct runs. */
internal fun jobParams(jobId: Int): JobParameters {
    val params = mockk<JobParameters>(relaxed = true)
    every { params.jobId } returns jobId
    return params
}

internal class TestNetworkConstraint(private val met: Boolean = true) : Constraint {
    override fun isMet(): Boolean = met

    override fun getFactoryKey(): String = "NetworkConstraint"

    override fun applyToJobInfo(jobInfoBuilder: JobInfo.Builder) {
        jobInfoBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
    }

    override fun getJobSchedulerKeyPart(): String = "NETWORK"
}
