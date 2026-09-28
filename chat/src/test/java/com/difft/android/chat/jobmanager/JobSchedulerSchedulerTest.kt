package com.difft.android.chat.jobmanager

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import com.difft.android.chat.dependencies.ApplicationDependencies
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [RecordingJobServiceShadow::class])
class JobSchedulerSchedulerTest {

    private val jobScheduler = mockk<JobScheduler>(relaxed = true)
    private val application = mockk<Application>(relaxed = true)
    private val jobManager: JobManager get() = sharedJobManager

    private val events get() = RecordingJobServiceShadow.events

    @Before
    fun setUp() {
        RecordingJobServiceShadow.reset()
        TestDependencyProvider.failure = null
        clearMocks(sharedJobManager)
        every { application.packageName } returns "com.difft.android"
        every { application.getSystemService(JobScheduler::class.java) } returns jobScheduler

        ApplicationDependencies.init(application, TestDependencyProvider)
        coJustRun { jobManager.wakeUpFromSystem() }
    }

    @After
    fun tearDown() {
        TestDependencyProvider.failure = null
        clearMocks(jobScheduler, application, sharedJobManager)
        RecordingJobServiceShadow.reset()
    }

    // -- schedule() --

    @Test
    fun `schedule skips registration when a job with the same id is already pending`() {
        val seen = awaitPendingCheck()

        JobSchedulerScheduler(application).schedule(0, listOf(TestNetworkConstraint()))

        assertTrue(seen.await(AWAIT_SECONDS, TimeUnit.SECONDS), "schedule never reached getPendingJob")
        verify(exactly = 0) { jobScheduler.schedule(any()) }
    }

    @Test
    fun `schedule builds a persisted job carrying the delay, network type and derived id`() {
        every { jobScheduler.getPendingJob(any()) } returns null
        val info = slot<JobInfo>()
        val scheduled = CountDownLatch(1)
        every { jobScheduler.schedule(capture(info)) } answers {
            scheduled.countDown()
            JobScheduler.RESULT_SUCCESS
        }

        JobSchedulerScheduler(application).schedule(60_000L, listOf(TestNetworkConstraint()))

        assertTrue(scheduled.await(AWAIT_SECONDS, TimeUnit.SECONDS), "job was never scheduled")
        val captured = info.captured
        assertTrue(captured.isPersisted, "job must survive process death")
        assertEquals(60_000L, captured.minLatencyMillis)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, captured.networkType)
        assertEquals("NETWORK".hashCode(), captured.id)
        assertEquals(persistentJobId(listOf(TestNetworkConstraint())), captured.id)
        // Every wake finishes asking for a re-run, so the backoff the framework waits out before
        // the next one is what keeps a killed process reachable — part of the contract.
        assertEquals(300_000L, captured.initialBackoffMillis)
        assertEquals(JobInfo.BACKOFF_POLICY_LINEAR, captured.backoffPolicy)
    }

    @Test
    fun `schedule registers only once when two concurrent calls share a constraint set`() {
        val pending = ConcurrentHashMap<Int, JobInfo>()
        val checked = CountDownLatch(2)
        every { jobScheduler.getPendingJob(any()) } answers {
            checked.countDown()
            pending[firstArg<Int>()]
        }
        every { jobScheduler.schedule(any()) } answers {
            val info = firstArg<JobInfo>()
            pending[info.id] = info
            JobScheduler.RESULT_SUCCESS
        }

        val scheduler = JobSchedulerScheduler(application)
        val start = CountDownLatch(1)
        val threads = List(2) {
            thread {
                start.await()
                scheduler.schedule(0, listOf(TestNetworkConstraint()))
            }
        }
        start.countDown()
        threads.forEach { it.join(AWAIT_SECONDS * 1000) }

        assertTrue(checked.await(AWAIT_SECONDS, TimeUnit.SECONDS), "both calls should reach the dedup check")
        verify(exactly = 1) { jobScheduler.schedule(any()) }
    }

    // -- schedule(): replace instead of dedup --

    @Test
    fun `schedule replaces a pending job when a delay-0 registration has an unmet constraint`() {
        every { jobScheduler.getPendingJob(any()) } returns mockk(relaxed = true)
        val info = slot<JobInfo>()
        val scheduled = CountDownLatch(1)
        every { jobScheduler.schedule(capture(info)) } answers {
            scheduled.countDown()
            JobScheduler.RESULT_SUCCESS
        }

        JobSchedulerScheduler(application).schedule(0, listOf(TestNetworkConstraint(met = false)))

        assertTrue(scheduled.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the pending job was never replaced")
        verify(exactly = 1) { jobScheduler.schedule(any()) }
        assertEquals(0L, info.captured.minLatencyMillis)
        assertEquals(NETWORK_JOB_ID, info.captured.id)
    }

    @Test
    fun `schedule keeps the dedup for a delay-0 registration whose constraint is met`() {
        val checked = awaitPendingCheck()

        JobSchedulerScheduler(application).schedule(0, listOf(TestNetworkConstraint(met = true)))

        assertTrue(checked.await(AWAIT_SECONDS, TimeUnit.SECONDS), "schedule never reached getPendingJob")
        verify(exactly = 0) { jobScheduler.schedule(any()) }
    }

    @Test
    fun `schedule keeps the dedup for a delayed registration with an unmet constraint`() {
        val checked = awaitPendingCheck()

        JobSchedulerScheduler(application).schedule(45_000L, listOf(TestNetworkConstraint(met = false)))

        assertTrue(checked.await(AWAIT_SECONDS, TimeUnit.SECONDS), "schedule never reached getPendingJob")
        verify(exactly = 0) { jobScheduler.schedule(any()) }
    }

    @Test
    fun `schedule keeps the dedup for the constraint-less group`() {
        val checked = awaitPendingCheck()

        JobSchedulerScheduler(application).schedule(0, emptyList())

        assertTrue(checked.await(AWAIT_SECONDS, TimeUnit.SECONDS), "schedule never reached getPendingJob")
        verify(exactly = 0) { jobScheduler.schedule(any()) }
    }

    @Test
    fun `schedule survives the framework rejecting the registration`() {
        every { jobScheduler.getPendingJob(any()) } returns null
        val attempted = CountDownLatch(1)
        every { jobScheduler.schedule(any()) } answers {
            attempted.countDown()
            throw IllegalStateException("apps may not schedule more than 100 distinct jobs")
        }

        withUncaughtExceptions { uncaught ->
            JobSchedulerScheduler(application).schedule(0, listOf(TestNetworkConstraint()))

            assertTrue(attempted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "registration was never attempted")
            assertNull(
                uncaught.poll(1, TimeUnit.SECONDS),
                "the rejection must not escape the executor task"
            )
        }
    }

    // -- SystemService: single wake --

    @Test
    fun `onStartJob wakes the manager and always asks for a re-run`() {
        val service = buildService()

        val started = service.onStartJob(params(PLAIN_JOB_ID))

        assertTrue(started, "onStartJob must keep the binding until the wake-up finishes it")
        assertEquals(listOf("finished:$PLAIN_JOB_ID:true"), drain(1))
    }

    @Test
    fun `onStopJob reports unfinished before completion and finished afterwards`() {
        val gate = CountDownLatch(1)
        coEvery { jobManager.wakeUpFromSystem() } answers {
            gate.await()
            events.add("wake")
        }
        val service = buildService()
        val params = params(PLAIN_JOB_ID)

        service.onStartJob(params)
        assertTrue(service.onStopJob(params), "a job stopped before it finished must be rescheduled")

        gate.countDown()
        assertEquals(listOf("wake"), drain(1))
        assertFalse(service.onStopJob(params), "a completed job must not request a reschedule")
        assertNull(pollEvent(), "a job the framework already stopped must not be finished too")
    }

    @Test
    fun `a job is completed only once`() {
        val service = buildService()
        val params = params(PLAIN_JOB_ID)

        service.onStartJob(params)
        assertEquals(listOf("finished:$PLAIN_JOB_ID:true"), drain(1))

        assertFalse(service.onStopJob(params))
        assertTrue(events.isEmpty(), "no second jobFinished should be issued")
    }

    @Test
    fun `a framework failure inside jobFinished does not break the wake-up`() {
        RecordingJobServiceShadow.throwOnFinish = true
        val service = buildService()
        val params = params(PLAIN_JOB_ID)

        service.onStartJob(params)

        assertEquals(listOf("finish-failed:$PLAIN_JOB_ID"), drain(1))
        assertFalse(service.onStopJob(params), "the in-flight entry must be released even on failure")
    }

    @Test
    fun `onStartJob still asks for a re-run when the job manager cannot be provided`() {
        failJobManagerLookup(IllegalStateException("job manager unavailable"))
        val service = buildService()
        val params = params(PLAIN_JOB_ID)

        val started = service.onStartJob(params)

        assertTrue(started)
        // The wake-up failed, so nothing drained storage; retiring the job is the idle
        // transition's business, never a failed wake's.
        assertEquals(listOf("finished:$PLAIN_JOB_ID:true"), drain(1))
        assertTrue(events.isEmpty(), "the job must be finished exactly once")
        assertFalse(service.onStopJob(params))
    }

    @Test
    fun `onNetworkChanged returns without invoking the default implementation`() {
        val service = buildService()

        service.onNetworkChanged(params(NETWORK_JOB_ID))

        assertTrue(events.isEmpty(), "onNetworkChanged must not touch job completion")
    }

    // -- SystemService: two runs of one jobId --

    @Test
    fun `a stale run cannot complete the entry a later run of the same jobId owns`() {
        // The framework stops a run that outlives its 10-minute cap and re-runs the same id on the
        // still-bound instance; the stale completion must not consume the new run's entry.
        val started = AtomicInteger()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        coEvery { jobManager.wakeUpFromSystem() } answers {
            // Only the first run is held, so the second one completes while the first is still in
            // the wake-up.
            if (started.getAndIncrement() == 0) {
                entered.countDown()
                gate.await()
            }
        }
        val service = buildService()
        val stale = params(PLAIN_JOB_ID)
        val current = params(PLAIN_JOB_ID)

        service.onStartJob(stale)
        assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the stale run never reached the wake-up")
        service.onStartJob(current)

        assertEquals(listOf("finished:$PLAIN_JOB_ID:true"), drain(1), "the current run must complete")

        gate.countDown()
        assertNull(pollEvent(), "the stale run must not issue a second completion")
        assertFalse(service.onStopJob(stale), "the stale run no longer owns the id")
        assertFalse(service.onStopJob(current), "the current run was already completed")
    }

    // -- SystemService: two jobIds bound to one instance --

    @Test
    fun `two concurrently bound jobIds are each finished independently`() {
        val service = buildService()
        val plain = params(PLAIN_JOB_ID)
        val network = params(NETWORK_JOB_ID)

        service.onStartJob(plain)
        service.onStartJob(network)

        val recorded = drain(2)
        assertTrue(recorded.contains("finished:$PLAIN_JOB_ID:true"), "plain job was never finished")
        assertTrue(recorded.contains("finished:$NETWORK_JOB_ID:true"), "network job was never finished")
        assertFalse(service.onStopJob(network))
        assertFalse(service.onStopJob(plain))
    }

    @Test
    fun `stopping one jobId leaves the other in flight and still finishes it`() {
        val gate = CountDownLatch(1)
        coEvery { jobManager.wakeUpFromSystem() } answers { gate.await() }
        val service = buildService()
        val plain = params(PLAIN_JOB_ID)
        val network = params(NETWORK_JOB_ID)

        service.onStartJob(plain)
        service.onStartJob(network)
        assertTrue(service.onStopJob(plain), "the stopped job must request a reschedule")

        gate.countDown()
        assertEquals(listOf("finished:$NETWORK_JOB_ID:true"), drain(1), "the untouched job must still finish")
        assertNull(pollEvent(), "the stopped job must not be finished too")
        assertFalse(service.onStopJob(plain), "the stopped job must not be reported unfinished twice")
    }

    // -- Helpers --

    private fun buildService(): JobSchedulerScheduler.SystemService =
        Robolectric.buildService(JobSchedulerScheduler.SystemService::class.java).create().get()

    private fun awaitPendingCheck(): CountDownLatch {
        val checked = CountDownLatch(1)
        every { jobScheduler.getPendingJob(any()) } answers {
            checked.countDown()
            mockk(relaxed = true)
        }
        return checked
    }

    /**
     * Makes the next `getJobManager()` go back to the provider and fail there. The instance is
     * memoised after the first successful lookup, so the cache has to be dropped for the provider
     * to be consulted again.
     */
    private fun failJobManagerLookup(error: RuntimeException) {
        TestDependencyProvider.failure = error
        ApplicationDependencies::class.java
            .getDeclaredField("jobManagerInstance")
            .apply { isAccessible = true }
            .set(ApplicationDependencies, null)
    }

    private fun params(jobId: Int): JobParameters = jobParams(jobId)

    private fun drain(count: Int): List<String> = events.drainInOrder(count, "event")

    /**
     * Waits briefly for an event that must never arrive. A bare emptiness check would pass simply
     * because the completion the row forbids had not been issued yet.
     */
    private fun pollEvent(): String? = events.poll(NO_EVENT_MILLIS, TimeUnit.MILLISECONDS)

    private companion object {
        /** How long a "nothing else is recorded" assertion gives the executor to prove otherwise. */
        const val NO_EVENT_MILLIS = 200L
    }
}
