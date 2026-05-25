package com.difft.android.chat.jobmanager

import android.app.Application
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.JobStorage
import com.difft.android.chat.util.Debouncer
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the new framework-layer read API [JobManager.findJobsInQueue] and the underlying
 * [JobController.findJobsInQueue] delegation. Covers design-report rows F1–F6.
 *
 * Tech: MockK + `runBlocking(Dispatchers.Default)` + JUnit 4 + Robolectric.
 *
 * **Why Robolectric:** [JobManager]'s internal [com.difft.android.chat.util.Debouncer] eagerly
 * creates a `Handler(Looper.getMainLooper())` in its constructor, which requires the Android
 * framework. We mirror the pattern in [JobManagerInitTest], which has the same root cause.
 * `mockk<Application>(relaxed = true)` for [JobManager] / [JobController] ctor.
 *
 * **Why `runBlocking(Dispatchers.Default)` (not `runTest`):** [JobManager.findJobsInQueue]
 * hops to `Dispatchers.Default.limitedParallelism(1)` via `withContext(managementDispatcher)`.
 * That dispatcher is a real thread pool — it is NOT under the test scheduler's control.
 * `runTest`'s virtual time scheduler does not advance for real dispatchers, so
 * `withTimeout(N)` from within `runTest` times out before the real-thread continuation
 * runs. `runBlocking` blocks the test thread on real time and lets the real continuation run.
 *
 * F4 / F4b operate on [JobController] directly (no JobManager init), so they don't need
 * `runBlocking` at all — they're plain synchronous tests.
 */
@RunWith(RobolectricTestRunner::class)
class JobManagerFindJobsInQueueTest {

    private val application = mockk<Application>(relaxed = true)
    private val jobStorage = mockk<JobStorage>(relaxed = true)
    private val jobInstantiator = mockk<JobInstantiator>(relaxed = true)
    private val constraintInstantiator = mockk<ConstraintInstantiator>(relaxed = true)
    private val dataSerializer = mockk<Data.Serializer>(relaxed = true)
    private val jobTracker = mockk<JobTracker>(relaxed = true)
    private val scheduler = mockk<Scheduler>(relaxed = true)
    private val debouncer = mockk<Debouncer>(relaxed = true)
    private val callback = mockk<JobController.Callback>(relaxed = true)

    @Before
    fun setUp() {
        // Sensible defaults for JobController construction; individual tests override as needed.
        every { dataSerializer.serialize(any()) } returns "{}"
        every { dataSerializer.deserialize(any()) } returns Data.EMPTY
    }

    @After
    fun tearDown() {
        clearMocks(
            jobStorage, jobInstantiator, constraintInstantiator,
            dataSerializer, jobTracker, scheduler, debouncer, callback
        )
    }

    // ----------------------------------------------------------------------------------------
    // F1 — happy-path delegation
    // ----------------------------------------------------------------------------------------

    @Test
    fun `F1 - findJobsInQueue delegates to JobController and returns its list in order`() =
        runBlocking(Dispatchers.Default) {
            val spec1 = buildJobSpec(id = "spec-1", queueKey = "QUEUE", createTime = 100)
            val spec2 = buildJobSpec(id = "spec-2", queueKey = "QUEUE", createTime = 200)
            // Stub storage so JobController.findJobsInQueue returns exactly this list.
            every { jobStorage.getJobsInQueue("QUEUE") } returns listOf(spec1, spec2)
            // Allow init to complete with no jobs.
            every { jobStorage.updateAllJobsToBePending() } just Runs

            val jobManager = newJobManager()

            val result = withTimeout(5_000) { jobManager.findJobsInQueue("QUEUE") }

            assertEquals(listOf(spec1, spec2), result)
            verify(exactly = 1) { jobStorage.getJobsInQueue("QUEUE") }
        }

    // ----------------------------------------------------------------------------------------
    // F2 — empty list propagation
    // ----------------------------------------------------------------------------------------

    @Test
    fun `F2 - findJobsInQueue returns empty list when no jobs in queue`() =
        runBlocking(Dispatchers.Default) {
            every { jobStorage.getJobsInQueue("EMPTY_QUEUE") } returns emptyList()
            every { jobStorage.updateAllJobsToBePending() } just Runs

            val jobManager = newJobManager()

            val result = withTimeout(5_000) { jobManager.findJobsInQueue("EMPTY_QUEUE") }

            assertTrue(result.isEmpty(), "Expected empty list but got: $result")
            verify(exactly = 1) { jobStorage.getJobsInQueue("EMPTY_QUEUE") }
        }

    // ----------------------------------------------------------------------------------------
    // F3 — initDeferred.await() ordering
    //
    // Approach: gate `jobStorage.init()` with a CountDownLatch. While the latch is closed,
    // JobManager's init coroutine is parked inside jobStorage.init(), so `initDeferred` is
    // NOT yet completed. We start `findJobsInQueue` in a launched coroutine and verify it
    // is still active (suspended). Then we release the latch; init completes; findJobsInQueue
    // resumes and delegates to JobController -> jobStorage.getJobsInQueue.
    // ----------------------------------------------------------------------------------------

    @Test
    fun `F3 - findJobsInQueue suspends until JobManager initDeferred completes`() =
        runBlocking(Dispatchers.Default) {
            val initGate = CountDownLatch(1)
            every { jobStorage.init() } answers {
                // Block JobManager's init coroutine until released.
                // 5s safety timeout so a buggy test cannot hang the suite.
                initGate.await(5, TimeUnit.SECONDS)
            }
            every { jobStorage.updateAllJobsToBePending() } just Runs

            val expectedSpec = buildJobSpec(id = "post-init", queueKey = "Q_F3", createTime = 1L)
            every { jobStorage.getJobsInQueue("Q_F3") } returns listOf(expectedSpec)

            val jobManager = newJobManager()

            // Launch findJobsInQueue concurrently. Because we're already inside
            // `runBlocking(Dispatchers.Default)`, this `launch` creates a real concurrent
            // coroutine on Dispatchers.Default.
            var result: List<JobSpec>? = null
            val findJob: Job = launch {
                result = jobManager.findJobsInQueue("Q_F3")
            }

            // Give the launched coroutine a real-time window to attempt the call and suspend
            // on initDeferred.await(). 200ms is generous on CI (the call is just a `withContext`
            // hop + an await).
            delay(200)

            // Pre-condition: findJobsInQueue must still be suspended (init not complete).
            // And jobStorage.getJobsInQueue must not have been called yet — the call is
            // sequenced after initDeferred.await().
            assertTrue(
                findJob.isActive,
                "findJobsInQueue should be suspended waiting for init, but it completed early"
            )
            verify(exactly = 0) { jobStorage.getJobsInQueue("Q_F3") }

            // Release init. JobManager's init coroutine should complete jobStorage.init(),
            // then signal initDeferred, and findJobsInQueue should resume.
            initGate.countDown()

            // Bounded wait for findJobsInQueue to finish.
            withTimeout(5_000) { findJob.join() }

            assertEquals(listOf(expectedSpec), result)
            verify(exactly = 1) { jobStorage.getJobsInQueue("Q_F3") }
        }

    // ----------------------------------------------------------------------------------------
    // F4 — JobController.findJobsInQueue against stubbed JobStorage (delegation + sort order)
    // ----------------------------------------------------------------------------------------

    @Test
    fun `F4 - JobController findJobsInQueue delegates to JobStorage preserving sort order`() {
        // Note: FastJobStorage.getJobsInQueue already sorts by createTime ascending. This test
        // verifies that JobController.findJobsInQueue does NOT alter the order coming back
        // from JobStorage. We stub JobStorage to return jobs already in sorted ascending
        // createTime order to simulate FastJobStorage's contract.
        val sortedSpecs = listOf(
            buildJobSpec(id = "a", queueKey = "QC", createTime = 100),
            buildJobSpec(id = "b", queueKey = "QC", createTime = 200),
            buildJobSpec(id = "c", queueKey = "QC", createTime = 300),
        )
        every { jobStorage.getJobsInQueue("QC") } returns sortedSpecs

        val controller = JobController(
            application, jobStorage, jobInstantiator, constraintInstantiator,
            dataSerializer, jobTracker, scheduler, debouncer, callback
        )

        val result = controller.findJobsInQueue("QC")

        // Order must exactly match what JobStorage returned — JobController is a pass-through.
        assertEquals(sortedSpecs, result)
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
        verify(exactly = 1) { jobStorage.getJobsInQueue("QC") }
    }

    @Test
    fun `F4b - JobController findJobsInQueue returns empty list when JobStorage has no matches`() {
        every { jobStorage.getJobsInQueue("EMPTY") } returns emptyList()

        val controller = JobController(
            application, jobStorage, jobInstantiator, constraintInstantiator,
            dataSerializer, jobTracker, scheduler, debouncer, callback
        )

        val result = controller.findJobsInQueue("EMPTY")

        assertTrue(result.isEmpty())
        verify(exactly = 1) { jobStorage.getJobsInQueue("EMPTY") }
    }

    // ----------------------------------------------------------------------------------------
    // F5 — concurrent N=10 calls serialized on managementDispatcher
    // ----------------------------------------------------------------------------------------

    @Test
    fun `F5 - 10 concurrent findJobsInQueue calls all complete without race`() =
        runBlocking(Dispatchers.Default) {
            val spec = buildJobSpec(id = "spec-c", queueKey = "QF5", createTime = 1L)
            every { jobStorage.getJobsInQueue("QF5") } returns listOf(spec)
            every { jobStorage.updateAllJobsToBePending() } just Runs

            val jobManager = newJobManager()

            // Launch 10 concurrent finders. The single-threaded managementDispatcher must
            // serialize these without throwing ConcurrentModificationException or producing
            // torn reads.
            val results = withTimeout(10_000) {
                (1..10).map { async { jobManager.findJobsInQueue("QF5") } }.map { it.await() }
            }

            assertEquals(10, results.size)
            results.forEachIndexed { i, list ->
                assertEquals(
                    listOf(spec), list,
                    "Concurrent call #$i returned unexpected list: $list"
                )
            }
            verify(exactly = 10) { jobStorage.getJobsInQueue("QF5") }
        }

    // ----------------------------------------------------------------------------------------
    // F6 — snapshot semantics: two reads return equal but independent lists
    // ----------------------------------------------------------------------------------------

    @Test
    fun `F6 - two findJobsInQueue calls return independent equal lists not the same instance`() =
        runBlocking(Dispatchers.Default) {
            // Stub answers { ... } so each invocation returns a freshly-constructed list.
            // (FastJobStorage's real .filter { … }.sortedBy { ... } also returns a fresh list
            // each call.)
            val spec = buildJobSpec(id = "snap-1", queueKey = "QF6", createTime = 1L)
            every { jobStorage.getJobsInQueue("QF6") } answers { listOf(spec) }
            every { jobStorage.updateAllJobsToBePending() } just Runs

            val jobManager = newJobManager()

            val first = withTimeout(5_000) { jobManager.findJobsInQueue("QF6") }
            val second = withTimeout(5_000) { jobManager.findJobsInQueue("QF6") }

            // Contract: lists are equal (by content)…
            assertEquals(first, second)
            assertEquals(listOf(spec), first)

            // …but each call must round-trip through storage — no caching. The strongest
            // assertion we can make about the snapshot contract (without depending on JVM
            // List-interning behavior) is that the API does NOT share state across calls:
            // jobStorage.getJobsInQueue was called exactly twice.
            verify(exactly = 2) { jobStorage.getJobsInQueue("QF6") }
        }

    // ----------------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------------

    /**
     * Construct a real [JobManager] with a stubbed-out [JobStorage] and stub factories.
     * Constraint observers list is empty to avoid any background registration. Storage init
     * is allowed to complete by default (test must override `jobStorage.init` if it wants
     * to gate init).
     */
    private fun newJobManager(): JobManager {
        val config = JobManager.Configuration.Builder()
            .setJobThreadCount(1)
            .setJobFactories(emptyMap())
            .setConstraintFactories(emptyMap())
            .setConstraintObservers(emptyList())
            .setDataSerializer(dataSerializer)
            .setJobStorage(jobStorage)
            .build()
        return JobManager(application, config)
    }

    private fun buildJobSpec(
        id: String = "test-job",
        factoryKey: String = "TestFactory",
        queueKey: String? = null,
        createTime: Long = System.currentTimeMillis(),
        nextRunAttemptTime: Long = 0,
        runAttempt: Int = 0,
        maxAttempts: Int = 3,
        lifespan: Long = com.difft.android.chat.jobmanager.Job.Parameters.IMMORTAL,
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
