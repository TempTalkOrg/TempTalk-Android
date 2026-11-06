package com.difft.android.chat.jobmanager

import android.app.Application
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.JobStorage
import com.difft.android.chat.jobs.DownloadAttachmentJob
import com.difft.android.chat.jobs.PushTextSendJob
import com.difft.android.chat.jobs.testJobHold
import com.difft.android.chat.util.Debouncer
import com.difft.android.test.fakes.TestMessagingPause
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [JobController] under the migration message pause (#1382): a deferred job goes back to the queue
 * with its attempt count unchanged, and the execution hold keeps send jobs from being picked while
 * messaging is not open without blocking other jobs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobControllerDeferralTest {

    private val application = mockk<Application>(relaxed = true)
    private val jobStorage = mockk<JobStorage>(relaxed = true)
    private val jobInstantiator = mockk<JobInstantiator>(relaxed = true)
    private val constraintInstantiator = mockk<ConstraintInstantiator>(relaxed = true)
    private val dataSerializer = mockk<Data.Serializer>(relaxed = true)
    private val jobTracker = mockk<JobTracker>(relaxed = true)
    private val scheduler = mockk<Scheduler>(relaxed = true)
    private val persistentScheduler = mockk<Scheduler>(relaxed = true)
    private val debouncer = mockk<Debouncer>(relaxed = true)
    private val callback = mockk<JobController.Callback>(relaxed = true)

    private val pause = TestMessagingPause().open()
    private lateinit var controller: JobController

    @Before
    fun setUp() {
        every { dataSerializer.serialize(any()) } returns SERIALIZED
        every { dataSerializer.deserialize(any()) } returns Data.EMPTY
        every { jobStorage.getConstraintSpecs(any()) } returns emptyList()
        every { jobInstantiator.instantiate(any(), any(), any()) } answers { jobFor(firstArg()) }
        controller = JobController(
            application, jobStorage, jobInstantiator, constraintInstantiator,
            dataSerializer, jobTracker, scheduler, persistentScheduler, debouncer, callback,
            testJobHold(pause.gate)
        )
    }

    // -- onDeferred --

    @Test
    fun `onDeferred keeps the attempt count and re-serializes the job`() {
        val job = mockJob("deferred", runAttempt = 2)
        val before = System.currentTimeMillis()

        controller.onDeferred(job)

        val nextRun = slot<Long>()
        verify(exactly = 1) {
            jobStorage.updateJobAfterRetry("deferred", false, 2, capture(nextRun), SERIALIZED)
        }
        assertTrue(nextRun.captured > before, "the next run time must be strictly in the future")
        verify { jobTracker.onStateChange(job, JobTracker.JobState.PENDING) }
        verify(exactly = 0) { job.onRetry() }
        verify(exactly = 0) { job.onFailure() }
    }

    @Test
    fun `onDeferred schedules a positive wake on the in-process scheduler`() {
        controller.onDeferred(mockJob("deferred-wake"))

        val delay = slot<Long>()
        verify(exactly = 1) { scheduler.schedule(capture(delay), emptyList()) }
        assertTrue(delay.captured > 0, "a zero delay is never scheduled by the in-app scheduler")
        verify(exactly = 0) { persistentScheduler.schedule(any(), any()) }
    }

    @Test
    fun `repeated deferrals never consume an attempt`() {
        val job = mockJob("many", runAttempt = 0)

        repeat(10) { controller.onDeferred(job) }

        verify(exactly = 10) { jobStorage.updateJobAfterRetry("many", false, 0, any(), any()) }
        verify(exactly = 0) { jobStorage.updateJobAfterRetry("many", any(), neq(0), any(), any()) }
    }

    // -- Execution hold --

    @Test
    fun `while paused a send job is skipped and the next eligible job runs`() = runTest {
        pause.pauseMessaging()
        every { jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any()) } returns listOf(
            spec("send", PushTextSendJob.KEY),
            spec("download", DownloadAttachmentJob.KEY),
        )

        val picked = controller.pullNextEligibleJobForExecution(JobPredicate.NONE, StandardTestDispatcher(testScheduler))

        assertEquals("download", picked.id)
        verify(exactly = 0) { jobStorage.updateJobRunningState("send", any()) }
    }

    @Test
    fun `while paused a queue of only send jobs runs nothing`() = runTest {
        pause.pauseMessaging()
        every { jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any()) } returns listOf(
            spec("send", PushTextSendJob.KEY),
        )

        val picked = withTimeoutOrNull(1_000) {
            controller.pullNextEligibleJobForExecution(JobPredicate.NONE, StandardTestDispatcher(testScheduler))
        }

        assertNull(picked)
        verify(exactly = 0) { jobStorage.updateJobRunningState(any(), any()) }
    }

    @Test
    fun `once messaging opens the held send job is picked first`() = runTest {
        pause.pauseMessaging().resumeMessaging()
        every { jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any()) } returns listOf(
            spec("send", PushTextSendJob.KEY),
            spec("download", DownloadAttachmentJob.KEY),
        )

        val picked = controller.pullNextEligibleJobForExecution(JobPredicate.NONE, StandardTestDispatcher(testScheduler))

        assertEquals("send", picked.id)
    }

    // -- Helpers --

    private fun mockJob(id: String, runAttempt: Int = 0): Job {
        val job = mockk<Job>(relaxed = true)
        every { job.id } returns id
        every { job.parameters } returns Job.Parameters.Builder(id).setMaxAttempts(3).build()
        every { job.getFactoryKey() } returns PushTextSendJob.KEY
        every { job.serialize() } returns Data.EMPTY
        every { job.runAttempt } returns runAttempt
        return job
    }

    private fun jobFor(factoryKey: String): Job = mockk<Job>(relaxed = true).also { job ->
        val id = if (factoryKey == PushTextSendJob.KEY) "send" else "download"
        every { job.id } returns id
        every { job.getFactoryKey() } returns factoryKey
    }

    private fun spec(id: String, factoryKey: String) = JobSpec(
        id = id,
        factoryKey = factoryKey,
        queueKey = "queue-$id",
        createTime = 1L,
        nextRunAttemptTime = 0L,
        runAttempt = 0,
        maxAttempts = 3,
        lifespan = Job.Parameters.IMMORTAL,
        serializedData = "{}",
        isRunning = false,
        isMemoryOnly = false,
    )

    private companion object {
        const val SERIALIZED = "{\"uploaded\":true}"
    }
}
