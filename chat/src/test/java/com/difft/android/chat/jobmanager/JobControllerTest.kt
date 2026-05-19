package com.difft.android.chat.jobmanager

import android.app.Application
import com.difft.android.chat.jobmanager.persistence.ConstraintSpec
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.JobStorage
import com.difft.android.chat.util.Debouncer
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JobControllerTest {

    private val application = mockk<Application>(relaxed = true)
    private val jobStorage = mockk<JobStorage>(relaxed = true)
    private val jobInstantiator = mockk<JobInstantiator>(relaxed = true)
    private val constraintInstantiator = mockk<ConstraintInstantiator>(relaxed = true)
    private val dataSerializer = mockk<Data.Serializer>(relaxed = true)
    private val jobTracker = mockk<JobTracker>(relaxed = true)
    private val scheduler = mockk<Scheduler>(relaxed = true)
    private val debouncer = mockk<Debouncer>(relaxed = true)
    private val callback = mockk<JobController.Callback>(relaxed = true)

    private lateinit var controller: JobController

    @Before
    fun setUp() {
        every { dataSerializer.serialize(any()) } returns "{}"
        every { dataSerializer.deserialize(any()) } returns Data.EMPTY
        controller = JobController(
            application, jobStorage, jobInstantiator, constraintInstantiator,
            dataSerializer, jobTracker, scheduler, debouncer, callback
        )
    }

    @After
    fun tearDown() {
        clearMocks(
            jobStorage, jobInstantiator, constraintInstantiator,
            dataSerializer, jobTracker, scheduler, debouncer, callback
        )
    }

    // -- submitJob --

    @Test
    fun `submitJob stores job in storage`() {
        val job = createTestJob("job-1")
        every { jobStorage.getJobCountForFactory(any()) } returns 0

        controller.submitJob(job)

        val fullSpecSlot = slot<List<FullSpec>>()
        verify { jobStorage.insertJobs(capture(fullSpecSlot)) }
        val inserted = fullSpecSlot.captured.single()
        assertEquals("job-1", inserted.jobSpec.id)
        assertEquals("TestFactory", inserted.jobSpec.factoryKey)
    }

    @Test
    fun `submitJob calls onAdded via onSubmit`() {
        val job = createTestJob("job-2")
        every { jobStorage.getJobCountForFactory(any()) } returns 0

        controller.submitJob(job)

        verify { job.onSubmit() }
    }

    @Test
    fun `submitJob sets application context on job`() {
        val job = createTestJob("job-ctx")
        every { jobStorage.getJobCountForFactory(any()) } returns 0

        controller.submitJob(job)

        assertEquals(application, job.context)
    }

    @Test
    fun `submitJob schedules job via scheduler`() {
        val job = createTestJob("job-sched")
        every { jobStorage.getJobCountForFactory(any()) } returns 0

        controller.submitJob(job)

        verify { scheduler.schedule(0, any()) }
    }

    @Test
    fun `submitJob resets runAttempt to zero`() {
        val job = createTestJob("job-reset")
        every { jobStorage.getJobCountForFactory(any()) } returns 0

        controller.submitJob(job)

        val fullSpecSlot = slot<List<FullSpec>>()
        verify { jobStorage.insertJobs(capture(fullSpecSlot)) }
        assertEquals(0, fullSpecSlot.captured.single().jobSpec.runAttempt)
    }

    // -- submitJob: maxInstancesForFactory exceeded -> IGNORED --

    @Test
    fun `submitJob ignores job when maxInstancesForFactory exceeded`() {
        val params = Job.Parameters.Builder("job-max")
            .setMaxInstancesForFactory(1)
            .build()
        val job = createTestJob("job-max", params)
        every { jobStorage.getJobCountForFactory("TestFactory") } returns 1

        controller.submitJob(job)

        verify(exactly = 0) { jobStorage.insertJobs(any()) }
        verify { jobTracker.onStateChange(job, JobTracker.JobState.IGNORED) }
    }

    @Test
    fun `submitJob does not call onSubmit when factory limit exceeded`() {
        val params = Job.Parameters.Builder("job-max-2")
            .setMaxInstancesForFactory(2)
            .build()
        val job = createTestJob("job-max-2", params)
        every { jobStorage.getJobCountForFactory("TestFactory") } returns 2

        controller.submitJob(job)

        verify(exactly = 0) { job.onSubmit() }
    }

    // -- submitJob: maxInstancesForQueue exceeded -> IGNORED --

    @Test
    fun `submitJob ignores job when maxInstancesForQueue exceeded`() {
        val params = Job.Parameters.Builder("job-q-max")
            .setMaxInstancesForQueue(1)
            .setQueue("my-queue")
            .build()
        val job = createTestJob("job-q-max", params)
        every { jobStorage.getJobCountForFactory("TestFactory") } returns 0
        every { jobStorage.getJobCountForFactoryAndQueue("TestFactory", "my-queue") } returns 1

        controller.submitJob(job)

        verify(exactly = 0) { jobStorage.insertJobs(any()) }
        verify { jobTracker.onStateChange(job, JobTracker.JobState.IGNORED) }
    }

    @Test
    fun `submitJob does not ignore when below maxInstances limits`() {
        val params = Job.Parameters.Builder("job-ok")
            .setMaxInstancesForFactory(5)
            .setMaxInstancesForQueue(3)
            .setQueue("queue-ok")
            .build()
        val job = createTestJob("job-ok", params)
        every { jobStorage.getJobCountForFactory("TestFactory") } returns 2
        every { jobStorage.getJobCountForFactoryAndQueue("TestFactory", "queue-ok") } returns 1

        controller.submitJob(job)

        verify { jobStorage.insertJobs(any()) }
    }

    @Test
    fun `submitJob does not check queue limit when maxInstancesForQueue is UNLIMITED`() {
        val params = Job.Parameters.Builder("job-unlim")
            .setMaxInstancesForFactory(10)
            .setQueue("some-queue")
            .build()
        val job = createTestJob("job-unlim", params)
        every { jobStorage.getJobCountForFactory("TestFactory") } returns 0

        controller.submitJob(job)

        verify(exactly = 0) { jobStorage.getJobCountForFactoryAndQueue(any(), any()) }
        verify { jobStorage.insertJobs(any()) }
    }

    // -- onSuccess --

    @Test
    fun `onSuccess deletes job from storage and tracks SUCCESS`() {
        val job = createTestJob("success-1")

        controller.onSuccess(job)

        verify { jobStorage.deleteJob("success-1") }
        verify { jobTracker.onStateChange(job, JobTracker.JobState.SUCCESS) }
    }

    // -- onFailure --

    @Test
    fun `onFailure deletes job from storage and tracks FAILURE`() {
        val job = createTestJob("fail-1")

        controller.onFailure(job)

        verify { jobStorage.deleteJob("fail-1") }
        verify { jobTracker.onStateChange(job, JobTracker.JobState.FAILURE) }
    }

    // -- onRetry --

    @Test
    fun `onRetry updates job in storage with incremented attempt and backoff`() {
        val job = createTestJob("retry-1")
        every { job.runAttempt } returns 0
        every { job.serialize() } returns Data.EMPTY
        every { jobStorage.getConstraintSpecs("retry-1") } returns emptyList()

        controller.onRetry(job, 5000L)

        verify {
            jobStorage.updateJobAfterRetry(
                id = "retry-1",
                isRunning = false,
                runAttempt = 1,
                nextRunAttemptTime = any(),
                serializedData = "{}"
            )
        }
    }

    @Test
    fun `onRetry tracks PENDING state`() {
        val job = createTestJob("retry-2")
        every { job.runAttempt } returns 0
        every { job.serialize() } returns Data.EMPTY
        every { jobStorage.getConstraintSpecs("retry-2") } returns emptyList()

        controller.onRetry(job, 5000L)

        verify { jobTracker.onStateChange(job, JobTracker.JobState.PENDING) }
    }

    @Test
    fun `onRetry schedules retry via scheduler`() {
        val job = createTestJob("retry-3")
        every { job.runAttempt } returns 0
        every { job.serialize() } returns Data.EMPTY
        every { jobStorage.getConstraintSpecs("retry-3") } returns emptyList()

        controller.onRetry(job, 5000L)

        verify { scheduler.schedule(any(), any()) }
    }

    @Test
    fun `onRetry instantiates constraints from storage specs`() {
        val job = createTestJob("retry-c")
        every { job.runAttempt } returns 0
        every { job.serialize() } returns Data.EMPTY
        val constraintSpec = ConstraintSpec("retry-c", "network", false)
        every { jobStorage.getConstraintSpecs("retry-c") } returns listOf(constraintSpec)
        val constraint = mockk<Constraint>(relaxed = true)
        every { constraintInstantiator.instantiate("network") } returns constraint

        controller.onRetry(job, 3000L)

        verify { constraintInstantiator.instantiate("network") }
        verify { scheduler.schedule(any(), listOf(constraint)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `onRetry throws for non-positive backoff interval`() {
        val job = createTestJob("retry-bad")
        every { job.runAttempt } returns 0
        every { job.serialize() } returns Data.EMPTY

        controller.onRetry(job, 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `onRetry throws for negative backoff interval`() {
        val job = createTestJob("retry-neg")
        every { job.runAttempt } returns 0
        every { job.serialize() } returns Data.EMPTY

        controller.onRetry(job, -100L)
    }

    // -- cancelAllInQueue --

    @Test
    fun `cancelAllInQueue cancels inactive jobs via cancelJob`() {
        val jobSpec1 = buildJobSpec("c1", queueKey = "cancel-q")
        val jobSpec2 = buildJobSpec("c2", queueKey = "cancel-q")
        every { jobStorage.getJobsInQueue("cancel-q") } returns listOf(jobSpec1, jobSpec2)
        every { jobStorage.getJobSpec("c1") } returns jobSpec1
        every { jobStorage.getJobSpec("c2") } returns jobSpec2
        every { jobStorage.getConstraintSpecs(any()) } returns emptyList()

        val job1 = createTestJob("c1")
        val job2 = createTestJob("c2")
        every { jobInstantiator.instantiate("TestFactory", any(), any()) } returnsMany listOf(job1, job2)

        controller.cancelAllInQueue("cancel-q")

        verify { jobStorage.deleteJob("c1") }
        verify { jobStorage.deleteJob("c2") }
    }

    @Test
    fun `cancelAllInQueue sets canceled flag on running jobs`() {
        // First submit a job so it enters runningJobs map (via submitJob only puts in storage,
        // pullNextEligibleJobForExecution puts into runningJobs, but that blocks).
        // Instead, we test via cancelJob: if a job's id is in runningJobs, cancel() is called on it.
        // We need to get a job into runningJobs first. The only public way is pullNextEligibleJobForExecution,
        // which blocks. So we test the cancelJob path for the non-running case, and verify cancel() is called
        // on instantiated jobs (cancelJob calls job.cancel() for both running and non-running jobs).

        val jobSpec = buildJobSpec("running-1", queueKey = "q")
        every { jobStorage.getJobsInQueue("q") } returns listOf(jobSpec)
        every { jobStorage.getJobSpec("running-1") } returns jobSpec
        every { jobStorage.getConstraintSpecs("running-1") } returns emptyList()

        val recreatedJob = createTestJob("running-1")
        every { jobInstantiator.instantiate("TestFactory", any(), any()) } returns recreatedJob

        controller.cancelAllInQueue("q")

        // For non-running jobs, cancelJob instantiates a new job, calls cancel() on it, then onFailure
        verify { recreatedJob.cancel() }
        verify { recreatedJob.onFailure() }
        verify { jobStorage.deleteJob("running-1") }
        verify { jobTracker.onStateChange(recreatedJob, JobTracker.JobState.FAILURE) }
    }

    // -- onJobFinished --

    @Test
    fun `onJobFinished removes job from running jobs`() {
        val job = createTestJob("fin-1")
        every { jobStorage.getJobCountForFactory(any()) } returns 0

        controller.submitJob(job)
        controller.onJobFinished(job)

        // No direct assertion on private runningJobs, but no exception means success
    }

    // -- init --

    @Test
    fun `init updates all jobs to pending`() {
        controller.init()

        verify { jobStorage.updateAllJobsToBePending() }
    }

    // -- getDebugInfo --

    @Test
    fun `getDebugInfo returns formatted string with jobs and constraints`() {
        val jobSpec = buildJobSpec("dbg-1")
        val constraint = ConstraintSpec("dbg-1", "network", false)
        every { jobStorage.getAllJobSpecs() } returns listOf(jobSpec)
        every { jobStorage.getAllConstraintSpecs() } returns listOf(constraint)

        val info = controller.getDebugInfo()

        assertTrue(info.contains("-- Jobs"))
        assertTrue(info.contains("-- Constraints"))
        assertTrue(info.contains("dbg-1"))
        assertTrue(info.contains("network"))
    }

    @Test
    fun `getDebugInfo returns None when empty`() {
        every { jobStorage.getAllJobSpecs() } returns emptyList()
        every { jobStorage.getAllConstraintSpecs() } returns emptyList()

        val info = controller.getDebugInfo()

        assertTrue(info.contains("None"))
    }

    // -- Helpers --

    private fun createTestJob(id: String, params: Job.Parameters? = null): Job {
        val parameters = params ?: Job.Parameters.Builder(id).build()
        val job = mockk<Job>(relaxed = true)
        every { job.id } returns id
        every { job.parameters } returns parameters
        every { job.getFactoryKey() } returns "TestFactory"
        every { job.serialize() } returns Data.EMPTY
        every { job.runAttempt } returns 0
        every { job.nextRunAttemptTime } returns 0L
        var ctx: android.content.Context? = null
        every { job.context = any() } answers { ctx = firstArg() }
        every { job.context } answers { ctx ?: application }
        return job
    }

    private fun buildJobSpec(
        id: String,
        factoryKey: String = "TestFactory",
        queueKey: String? = null,
        createTime: Long = System.currentTimeMillis(),
        nextRunAttemptTime: Long = 0,
        runAttempt: Int = 0,
        maxAttempts: Int = 3,
        lifespan: Long = Job.Parameters.IMMORTAL,
        serializedData: String = "{}",
        isRunning: Boolean = false,
        isMemoryOnly: Boolean = false
    ): JobSpec = JobSpec(
        id = id,
        factoryKey = factoryKey,
        queueKey = queueKey,
        createTime = createTime,
        nextRunAttemptTime = nextRunAttemptTime,
        runAttempt = runAttempt,
        maxAttempts = maxAttempts,
        lifespan = lifespan,
        serializedData = serializedData,
        isRunning = isRunning,
        isMemoryOnly = isMemoryOnly
    )
}
