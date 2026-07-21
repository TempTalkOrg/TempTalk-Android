package com.difft.android.chat.jobmanager

import android.app.Application
import com.difft.android.base.log.WCDBKeyUnavailableException
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [JobRunner], verifying the `runJob()` logic indirectly through `launchIn`.
 *
 * [JobRunner.launchIn] launches on [Dispatchers.IO] (real threads), so we cannot
 * use [StandardTestDispatcher] to control execution timing. Instead we use:
 * - [UnconfinedTestDispatcher] as `managementDispatcher` so that
 *   `withContext(managementDispatcher)` blocks execute immediately inline.
 * - [CompletableDeferred] signals from the mock [JobController.pullNextEligibleJobForExecution]
 *   to know when the runner has finished processing a job and entered the next pull (blocking).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobRunnerTest {

    private val application = mockk<Application>(relaxed = true)
    private val jobController = mockk<JobController>(relaxed = true)
    private val jobPredicate = JobPredicate.NONE

    /** Unconfined so withContext(managementDispatcher) executes inline on the IO thread. */
    private val managementDispatcher = UnconfinedTestDispatcher()

    private lateinit var runnerScope: CoroutineScope
    private lateinit var jobRunner: JobRunner

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.chat.util.WakeLockUtil")
        every { com.difft.android.chat.util.WakeLockUtil.acquire(any(), any(), any(), any()) } returns null
        every { com.difft.android.chat.util.WakeLockUtil.release(any(), any()) } returns Unit

        runnerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        jobRunner = JobRunner(
            application = application,
            id = 1,
            jobController = jobController,
            jobPredicate = jobPredicate,
            managementDispatcher = managementDispatcher
        )
    }

    @After
    fun tearDown() {
        runnerScope.cancel()
        unmockkStatic("com.difft.android.chat.util.WakeLockUtil")
        clearMocks(jobController)
    }

    // -- Success path --

    @Test
    fun `launchIn calls onJobFinished and onSuccess when job run returns success`() = runTest {
        val job = createTestJob("success-1")
        coEvery { job.run() } returns Job.Result.success()

        val done = setupControllerToReturnJobOnce(job)
        jobRunner.launchIn(runnerScope)
        done.await()

        coVerify { jobController.onJobFinished(job) }
        coVerify { jobController.onSuccess(job) }
    }

    // -- Retry path --

    @Test
    fun `launchIn calls onJobFinished and onRetry when job run returns retry`() = runTest {
        val backoff = 5000L
        val job = createTestJob("retry-1", maxAttempts = 5)
        coEvery { job.run() } returns Job.Result.retry(backoff)

        val done = setupControllerToReturnJobOnce(job)
        jobRunner.launchIn(runnerScope)
        done.await()

        coVerify { jobController.onJobFinished(job) }
        coVerify { jobController.onRetry(job, backoff) }
        verify { job.onRetry() }
    }

    // -- Failure path --

    @Test
    fun `launchIn calls onJobFinished onFailure and job onFailure when job run returns failure`() = runTest {
        val job = createTestJob("fail-1")
        coEvery { job.run() } returns Job.Result.failure()

        val done = setupControllerToReturnJobOnce(job)
        jobRunner.launchIn(runnerScope)
        done.await()

        coVerify { jobController.onJobFinished(job) }
        coVerify { jobController.onFailure(job) }
        verify { job.onFailure() }
    }

    // -- Expired job --

    @Test
    fun `launchIn fails expired job without calling run`() = runTest {
        val job = createTestJob(
            id = "expired-1",
            createTime = 1000L,
            lifespan = 100L // expired long ago (createTime + lifespan = 1100 < now)
        )

        val done = setupControllerToReturnJobOnce(job)
        jobRunner.launchIn(runnerScope)
        done.await()

        coVerify(exactly = 0) { job.run() }
        coVerify { jobController.onJobFinished(job) }
        coVerify { jobController.onFailure(job) }
        verify { job.onFailure() }
    }

    // -- Canceled job --

    @Test
    fun `launchIn fails canceled job even when run returns success`() = runTest {
        val job = createTestJob("canceled-1")
        coEvery { job.run() } returns Job.Result.success()
        every { job.isCanceled() } returns true

        val done = setupControllerToReturnJobOnce(job)
        jobRunner.launchIn(runnerScope)
        done.await()

        coVerify { jobController.onJobFinished(job) }
        coVerify { jobController.onFailure(job) }
        verify { job.onFailure() }
        coVerify(exactly = 0) { jobController.onSuccess(job) }
    }

    // -- maxAttempts exceeded --

    @Test
    fun `launchIn converts retry to failure when maxAttempts exceeded`() = runTest {
        val job = createTestJob("max-attempts-1", maxAttempts = 3)
        coEvery { job.run() } returns Job.Result.retry(1000L)
        every { job.runAttempt } returns 2 // runAttempt + 1 = 3 >= maxAttempts(3)

        val done = setupControllerToReturnJobOnce(job)
        jobRunner.launchIn(runnerScope)
        done.await()

        coVerify { jobController.onJobFinished(job) }
        coVerify { jobController.onFailure(job) }
        verify { job.onFailure() }
        coVerify(exactly = 0) { jobController.onRetry(job, any()) }
    }

    @Test
    fun `launchIn allows retry when maxAttempts is UNLIMITED`() = runTest {
        val backoff = 2000L
        val job = createTestJob("unlimited-attempts", maxAttempts = Job.Parameters.UNLIMITED)
        coEvery { job.run() } returns Job.Result.retry(backoff)
        every { job.runAttempt } returns 100 // high attempt count, but UNLIMITED

        val done = setupControllerToReturnJobOnce(job)
        jobRunner.launchIn(runnerScope)
        done.await()

        coVerify { jobController.onRetry(job, backoff) }
        verify { job.onRetry() }
    }

    // -- CancellationException --

    @Test
    fun `launchIn rethrows CancellationException from job run`() = runTest {
        val job = createTestJob("cancel-ex-1")
        coEvery { job.run() } throws CancellationException("coroutine cancelled")

        // For CancellationException, the runner coroutine gets cancelled before
        // reaching the second pull. We use a latch on the first pull instead.
        val pullCalled = CompletableDeferred<Unit>()
        coEvery { jobController.pullNextEligibleJobForExecution(any(), any()) } coAnswers {
            pullCalled.complete(Unit)
            job
        }

        val coroutineJob = jobRunner.launchIn(runnerScope)
        pullCalled.await()
        // Wait for the coroutine to finish (it will be cancelled by CancellationException)
        coroutineJob.join()

        // The CancellationException should propagate and cancel the coroutine,
        // NOT be caught as a failure
        coVerify(exactly = 0) { jobController.onFailure(job) }
        coVerify(exactly = 0) { jobController.onSuccess(job) }
    }

    // -- Generic exception treated as failure --

    @Test
    fun `launchIn treats generic exception from job run as failure`() = runTest {
        val job = createTestJob("exception-1")
        coEvery { job.run() } throws RuntimeException("unexpected")

        val done = setupControllerToReturnJobOnce(job)
        jobRunner.launchIn(runnerScope)
        done.await()

        coVerify { jobController.onJobFinished(job) }
        coVerify { jobController.onFailure(job) }
        verify { job.onFailure() }
    }

    // -- WakeLock released on exception --

    @Test
    fun `launchIn releases wake lock even when job run throws exception`() = runTest {
        val wakeLock = mockk<android.os.PowerManager.WakeLock>(relaxed = true)
        every { com.difft.android.chat.util.WakeLockUtil.acquire(any(), any(), any(), any()) } returns wakeLock

        val job = createTestJob("wakelock-1")
        coEvery { job.run() } throws RuntimeException("boom")

        val done = setupControllerToReturnJobOnce(job)
        jobRunner.launchIn(runnerScope)
        done.await()

        verify { com.difft.android.chat.util.WakeLockUtil.release(wakeLock, "wakelock-1") }
    }

    @Test
    fun `launchIn releases wake lock on success`() = runTest {
        val wakeLock = mockk<android.os.PowerManager.WakeLock>(relaxed = true)
        every { com.difft.android.chat.util.WakeLockUtil.acquire(any(), any(), any(), any()) } returns wakeLock

        val job = createTestJob("wakelock-success")
        coEvery { job.run() } returns Job.Result.success()

        val done = setupControllerToReturnJobOnce(job)
        jobRunner.launchIn(runnerScope)
        done.await()

        verify { com.difft.android.chat.util.WakeLockUtil.release(wakeLock, "wakelock-success") }
    }

    // -- fail-soft key-unavailable loop exit --

    @Test
    fun `G1 launchIn breaks loop and completes normally when pull throws WCDBKeyUnavailableException`() = runTest {
        // Runner must stop cleanly (break), not crash or spin; not a CancellationException subtype.
        coEvery { jobController.pullNextEligibleJobForExecution(any(), any()) } throws
            WCDBKeyUnavailableException("cipher key unavailable")

        val coroutineJob = jobRunner.launchIn(runnerScope)
        coroutineJob.join()

        assertTrue("runner should complete (loop broke)", coroutineJob.isCompleted)
        assertFalse("break is a clean exit, not a cancellation/crash", coroutineJob.isCancelled)
        coVerify(exactly = 0) { jobController.onSuccess(any()) }
        coVerify(exactly = 0) { jobController.onFailure(any()) }
    }

    // -- launchIn loops and processes multiple jobs --

    @Test
    fun `launchIn processes multiple jobs sequentially`() = runTest {
        val job1 = createTestJob("multi-1")
        val job2 = createTestJob("multi-2")
        coEvery { job1.run() } returns Job.Result.success()
        coEvery { job2.run() } returns Job.Result.success()

        val allDone = CompletableDeferred<Unit>()
        var callCount = 0
        coEvery { jobController.pullNextEligibleJobForExecution(any(), any()) } coAnswers {
            callCount++
            when (callCount) {
                1 -> job1
                2 -> job2
                else -> {
                    allDone.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    error("unreachable")
                }
            }
        }

        jobRunner.launchIn(runnerScope)
        allDone.await()

        coVerify { jobController.onSuccess(job1) }
        coVerify { jobController.onSuccess(job2) }
    }

    // -- Helpers --

    private fun createTestJob(
        id: String,
        createTime: Long = System.currentTimeMillis(),
        lifespan: Long = Job.Parameters.IMMORTAL,
        maxAttempts: Int = 3
    ): Job {
        val parameters = Job.Parameters.Builder(id)
            .setCreateTime(createTime)
            .setLifespan(lifespan)
            .setMaxAttempts(maxAttempts)
            .build()
        val job = mockk<Job>(relaxed = true)
        every { job.id } returns id
        every { job.parameters } returns parameters
        every { job.getFactoryKey() } returns "TestFactory"
        every { job.serialize() } returns Data.EMPTY
        every { job.runAttempt } returns 0
        every { job.nextRunAttemptTime } returns 0L
        every { job.isCanceled() } returns false
        every { job.canceled } returns false
        return job
    }

    /**
     * Sets up the mock [JobController] to return [job] on the first call to
     * [pullNextEligibleJobForExecution], then suspend indefinitely on subsequent calls.
     *
     * Returns a [CompletableDeferred] that completes when the runner has finished
     * processing the first job and enters the second (blocking) pull call. This
     * signals to the test that all side effects (onJobFinished, onSuccess, etc.) have
     * been executed and are safe to verify.
     */
    private fun setupControllerToReturnJobOnce(job: Job): CompletableDeferred<Unit> {
        val done = CompletableDeferred<Unit>()
        var called = false
        coEvery { jobController.pullNextEligibleJobForExecution(any(), any()) } coAnswers {
            if (!called) {
                called = true
                job
            } else {
                // Signal that the first job is fully processed
                done.complete(Unit)
                // Suspend forever — simulates waiting for next job
                CompletableDeferred<Unit>().await()
                error("unreachable")
            }
        }
        return done
    }
}
