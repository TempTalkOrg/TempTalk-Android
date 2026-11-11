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







internal const val AWAIT_SECONDS = 5L


internal const val PLAIN_JOB_ID = 0

internal val NETWORK_JOB_ID = "NETWORK".hashCode()





internal fun <T> LinkedBlockingQueue<T>.drainInOrder(count: Int, label: String): List<T> = List(count) {
    poll(AWAIT_SECONDS, TimeUnit.SECONDS)
        ?: error("timed out waiting for $label ${it + 1} of $count")
}







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






@Implements(JobService::class)
open class RecordingJobServiceShadow : ShadowService() {

    @Implementation
    protected fun jobFinished(params: JobParameters, needsReschedule: Boolean) {
        onFinish?.invoke()
        if (throwOnFinish) {
            
            
            events.add("finish-failed:${params.jobId}")
            throw IllegalStateException("Job already finished")
        }
        events.add("finished:${params.jobId}:$needsReschedule")
    }

    companion object {
        val events: LinkedBlockingQueue<String> = LinkedBlockingQueue()

        @Volatile
        var throwOnFinish: Boolean = false

        
        @Volatile
        var onFinish: (() -> Unit)? = null

        fun reset() {
            events.clear()
            throwOnFinish = false
            onFinish = null
        }
    }
}





internal val sharedJobManager: JobManager = mockk(relaxed = true)

internal object TestDependencyProvider : ApplicationDependencies.Provider {
    
    @Volatile
    var failure: RuntimeException? = null

    override fun provideJobManager(): JobManager = failure?.let { throw it } ?: sharedJobManager

    override fun provideExoPlayerPool(): SimpleExoPlayerPool =
        throw UnsupportedOperationException("not used by these tests")
}


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
