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











@OptIn(ExperimentalCoroutinesApi::class)
class JobRunnerTest {

    private val application = mockk<Application>(relaxed = true)
    private val jobController = mockk<JobController>(relaxed = true)
    private val jobPredicate = JobPredicate.NONE

    
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

    

    @Test
    fun `launchIn fails expired job without calling run`() = runTest {
        val job = createTestJob(
            id = "expired-1",
            createTime = 1000L,
            lifespan = 100L 
        )

        val done = setupControllerToReturnJobOnce(job)
        jobRunner.launchIn(runnerScope)
        done.await()

        coVerify(exactly = 0) { job.run() }
        coVerify { jobController.onJobFinished(job) }
        coVerify { jobController.onFailure(job) }
        verify { job.onFailure() }
    }

    

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

    

    @Test
    fun `launchIn converts retry to failure when maxAttempts exceeded`() = runTest {
        val job = createTestJob("max-attempts-1", maxAttempts = 3)
        coEvery { job.run() } returns Job.Result.retry(1000L)
        every { job.runAttempt } returns 2 

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
        every { job.runAttempt } returns 100 

        val done = setupControllerToReturnJobOnce(job)
        jobRunner.launchIn(runnerScope)
        done.await()

        coVerify { jobController.onRetry(job, backoff) }
        verify { job.onRetry() }
    }

    

    @Test
    fun `launchIn keeps running after a stray CancellationException from job run`() = runTest {
        val strayJob = createTestJob("stray-cancel-1")
        val nextJob = createTestJob("stray-cancel-2")
        coEvery { strayJob.run() } throws CancellationException("stray")
        coEvery { nextJob.run() } returns Job.Result.success()

        val allDone = CompletableDeferred<Unit>()
        var callCount = 0
        coEvery { jobController.pullNextEligibleJobForExecution(any(), any()) } coAnswers {
            callCount++
            when (callCount) {
                1 -> strayJob
                2 -> nextJob
                else -> {
                    allDone.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    error("unreachable")
                }
            }
        }

        val coroutineJob = jobRunner.launchIn(runnerScope)
        allDone.await()

        
        coVerify { jobController.onFailure(strayJob) }
        coVerify { jobController.onSuccess(nextJob) }
        assertTrue("runner must still be active", coroutineJob.isActive)
    }

    @Test
    fun `launchIn ends runner when its own scope is cancelled while job run throws CancellationException`() = runTest {
        val job = createTestJob("real-cancel-1")
        val inRun = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { job.run() } coAnswers {
            inRun.complete(Unit)
            release.await()
            throw CancellationException("coroutine cancelled")
        }
        coEvery { jobController.pullNextEligibleJobForExecution(any(), any()) } returns job

        val coroutineJob = jobRunner.launchIn(runnerScope)
        inRun.await()
        coroutineJob.cancel()
        release.complete(Unit)
        coroutineJob.join()

        assertTrue(coroutineJob.isCancelled)
        coVerify(exactly = 0) { jobController.onFailure(job) }
        coVerify(exactly = 0) { jobController.onSuccess(job) }
    }

    

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

    

    @Test
    fun `G1 launchIn breaks loop and completes normally when pull throws WCDBKeyUnavailableException`() = runTest {
        
        coEvery { jobController.pullNextEligibleJobForExecution(any(), any()) } throws
            WCDBKeyUnavailableException("cipher key unavailable")

        val coroutineJob = jobRunner.launchIn(runnerScope)
        coroutineJob.join()

        assertTrue("runner should complete (loop broke)", coroutineJob.isCompleted)
        assertFalse("break is a clean exit, not a cancellation/crash", coroutineJob.isCancelled)
        coVerify(exactly = 0) { jobController.onSuccess(any()) }
        coVerify(exactly = 0) { jobController.onFailure(any()) }
    }

    

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

    








    private fun setupControllerToReturnJobOnce(job: Job): CompletableDeferred<Unit> {
        val done = CompletableDeferred<Unit>()
        var called = false
        coEvery { jobController.pullNextEligibleJobForExecution(any(), any()) } coAnswers {
            if (!called) {
                called = true
                job
            } else {
                
                done.complete(Unit)
                
                CompletableDeferred<Unit>().await()
                error("unreachable")
            }
        }
        return done
    }
}
