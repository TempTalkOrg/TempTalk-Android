package com.difft.android.chat.jobmanager

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
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
        JobSchedulerScheduler.SystemService.acquireWakeLock = { null }
    }

    @After
    fun tearDown() {
        JobSchedulerScheduler.SystemService.acquireWakeLock = defaultAcquireWakeLock
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
        val woke = CountDownLatch(1)
        coEvery { jobManager.wakeUpFromSystem() } answers { woke.countDown() }
        val service = buildService()

        val started = service.onStartJob(params(PLAIN_JOB_ID))

        assertTrue(started, "the run is completed asynchronously, so onStartJob keeps it open")
        assertEquals(listOf("finished:$PLAIN_JOB_ID:true"), drain(1))
        assertTrue(woke.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the job manager was never woken")
    }

    @Test
    fun `the run is completed without waiting for the job manager to finish waking`() {
        // Models a cold start: the manager's init is still loading storage when the job arrives.
        val gate = CountDownLatch(1)
        val woke = CountDownLatch(1)
        coEvery { jobManager.wakeUpFromSystem() } answers {
            gate.await()
            woke.countDown()
        }
        val service = buildService()

        service.onStartJob(params(PLAIN_JOB_ID))

        assertEquals(listOf("finished:$PLAIN_JOB_ID:true"), drain(1), "completion must not wait for the wake")
        assertEquals(1L, woke.count, "the wake is still in progress after the run completed")
        gate.countDown()
        assertTrue(woke.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the wake must still be delivered")
    }

    @Test
    fun `the wake lock is held across completion and the wake, and released afterwards`() {
        val order = LinkedBlockingQueue<String>()
        val lock = newWakeLock()
        JobSchedulerScheduler.SystemService.acquireWakeLock = { lock.acquire(); lock }
        RecordingJobServiceShadow.onFinish = { order.add("finish held=${lock.isHeld}") }
        coEvery { jobManager.wakeUpFromSystem() } answers { order.add("wake held=${lock.isHeld}") }
        val service = buildService()

        service.onStartJob(params(PLAIN_JOB_ID))

        assertEquals(listOf("finish held=true", "wake held=true"), order.drainInOrder(2, "step"))
        assertTrue(awaitReleased(lock), "the wake lock must be released once the wake is delivered")
    }

    @Test
    fun `the wake lock is released when the job manager cannot be provided`() {
        failJobManagerLookup(IllegalStateException("job manager unavailable"))
        val lock = newWakeLock()
        JobSchedulerScheduler.SystemService.acquireWakeLock = { lock.acquire(); lock }
        val service = buildService()

        service.onStartJob(params(PLAIN_JOB_ID))

        assertEquals(listOf("finished:$PLAIN_JOB_ID:true"), drain(1))
        assertTrue(awaitReleased(lock), "the wake lock must not leak")
    }

    @Test
    fun `a stalled wake-lock acquisition is completed by the main-thread fallback exactly once`() {
        val gate = CountDownLatch(1)
        JobSchedulerScheduler.SystemService.acquireWakeLock = { gate.await(); null }
        val service = buildService()

        service.onStartJob(params(PLAIN_JOB_ID))
        assertNull(pollEvent(), "nothing completes the run while acquisition is stalled")

        ShadowLooper.idleMainLooper(FALLBACK_MILLIS, TimeUnit.MILLISECONDS)
        assertEquals(listOf("finished:$PLAIN_JOB_ID:true"), drain(1), "the fallback must complete the run")

        gate.countDown()
        assertNull(pollEvent(), "the background path must not complete the run a second time")
    }

    @Test
    fun `onStopJob asks for a reschedule before and after completion`() {
        val gate = CountDownLatch(1)
        JobSchedulerScheduler.SystemService.acquireWakeLock = { gate.await(); null }
        val service = buildService()
        val params = params(PLAIN_JOB_ID)

        service.onStartJob(params)
        assertTrue(service.onStopJob(params), "a job stopped before it finished must be rescheduled")

        gate.countDown()
        assertNull(pollEvent(), "a job the framework already stopped must not be finished too")
        // A stop the framework queued before our completion message can still be handled after
        // the entry is gone; answering false there would drop the non-periodic job.
        assertTrue(service.onStopJob(params), "a stop that lands after completion must still reschedule")
    }

    @Test
    fun `a job is completed only once`() {
        val service = buildService()
        val params = params(PLAIN_JOB_ID)

        service.onStartJob(params)
        assertEquals(listOf("finished:$PLAIN_JOB_ID:true"), drain(1))

        assertTrue(service.onStopJob(params))
        ShadowLooper.idleMainLooper(FALLBACK_MILLIS, TimeUnit.MILLISECONDS)
        assertNull(pollEvent(), "neither the stop nor the cancelled fallback may finish it again")
    }

    @Test
    fun `a framework failure inside jobFinished does not break the wake-up`() {
        RecordingJobServiceShadow.throwOnFinish = true
        val woke = CountDownLatch(1)
        coEvery { jobManager.wakeUpFromSystem() } answers { woke.countDown() }
        val service = buildService()

        service.onStartJob(params(PLAIN_JOB_ID))

        assertEquals(listOf("finish-failed:$PLAIN_JOB_ID"), drain(1))
        assertTrue(woke.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the wake must still be delivered")
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
        assertNull(pollEvent(), "the job must be finished exactly once")
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
        // The framework stops a run and re-runs the same id on the still-bound instance; the
        // stale run's late completion must not consume the new run's entry.
        val acquired = AtomicInteger()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        JobSchedulerScheduler.SystemService.acquireWakeLock = {
            // Only the first run is held, so the second one completes while the first is stalled.
            if (acquired.getAndIncrement() == 0) {
                entered.countDown()
                gate.await()
            }
            null
        }
        val service = buildService()
        val stale = params(PLAIN_JOB_ID)
        val current = params(PLAIN_JOB_ID)

        service.onStartJob(stale)
        assertTrue(entered.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the stale run never reached acquisition")
        service.onStartJob(current)

        assertEquals(listOf("finished:$PLAIN_JOB_ID:true"), drain(1), "the current run must complete")

        gate.countDown()
        assertNull(pollEvent(), "the stale run must not issue a second completion")
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
        assertNull(pollEvent(), "each job must be finished exactly once")
    }

    @Test
    fun `stopping one jobId leaves the other in flight and still finishes it`() {
        val gate = CountDownLatch(1)
        JobSchedulerScheduler.SystemService.acquireWakeLock = { gate.await(); null }
        val service = buildService()
        val plain = params(PLAIN_JOB_ID)
        val network = params(NETWORK_JOB_ID)

        service.onStartJob(plain)
        service.onStartJob(network)
        assertTrue(service.onStopJob(plain), "the stopped job must request a reschedule")

        gate.countDown()
        assertEquals(listOf("finished:$NETWORK_JOB_ID:true"), drain(1), "the untouched job must still finish")
        assertNull(pollEvent(), "the stopped job must not be finished too")
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

    /** A Robolectric-backed lock; the framework class is final and cannot be mocked inline. */
    private fun newWakeLock(): PowerManager.WakeLock =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:wake")
            .apply { setReferenceCounted(false) }

    private fun awaitReleased(lock: PowerManager.WakeLock): Boolean {
        val deadline = System.currentTimeMillis() + AWAIT_SECONDS * 1000
        while (lock.isHeld && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return !lock.isHeld
    }

    private fun drain(count: Int): List<String> = events.drainInOrder(count, "event")

    /**
     * Waits briefly for an event that must never arrive. A bare emptiness check would pass simply
     * because the completion the row forbids had not been issued yet.
     */
    private fun pollEvent(): String? = events.poll(NO_EVENT_MILLIS, TimeUnit.MILLISECONDS)

    private companion object {
        /** How long a "nothing else is recorded" assertion gives the executor to prove otherwise. */
        const val NO_EVENT_MILLIS = 200L

        /** Past the service's 2-second main-thread fallback. */
        const val FALLBACK_MILLIS = 2_500L

        val defaultAcquireWakeLock = JobSchedulerScheduler.SystemService.acquireWakeLock
    }
}
