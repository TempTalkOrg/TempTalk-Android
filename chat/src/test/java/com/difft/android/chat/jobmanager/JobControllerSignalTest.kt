package com.difft.android.chat.jobmanager

import android.app.Application
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobmanager.persistence.JobStorage
import com.difft.android.chat.util.Debouncer
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the signal/wake mechanism in [JobController]:
 * - [JobController.pullNextEligibleJobForExecution] suspends via [CompletableDeferred] when
 *   no eligible job is found, and resumes when [JobController.wakeUp] (or internal
 *   [signalJobAvailable]) is called.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobControllerSignalTest {

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

    private val predicate = JobPredicate.NONE

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

    // -- Test 1: Job available immediately --

    @Test
    fun `pullNextEligibleJobForExecution returns immediately when job is available`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val jobSpec = buildJobSpec("imm-1")
        val mockJob = createTestJob("imm-1")

        every {
            jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any())
        } returns listOf(jobSpec)
        every { jobStorage.getConstraintSpecs("imm-1") } returns emptyList()
        every { jobInstantiator.instantiate("TestFactory", any(), any()) } returns mockJob

        val result = controller.pullNextEligibleJobForExecution(predicate, testDispatcher)

        assertEquals("imm-1", result.id)
        verify { jobStorage.updateJobRunningState("imm-1", true) }
        verify { jobTracker.onStateChange(mockJob, JobTracker.JobState.RUNNING) }
    }

    // -- Test 2: Job not available, then signaled --

    @Test
    fun `pullNextEligibleJobForExecution suspends then resumes on signal`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val jobSpec = buildJobSpec("sig-1")
        val mockJob = createTestJob("sig-1")

        // First call: no jobs available. Second call (after signal): job available.
        var callCount = 0
        every {
            jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any())
        } answers {
            callCount++
            if (callCount <= 1) emptyList() else listOf(jobSpec)
        }
        every { jobStorage.getConstraintSpecs("sig-1") } returns emptyList()
        every { jobInstantiator.instantiate("TestFactory", any(), any()) } returns mockJob

        var result: Job? = null
        val pullJob = launch {
            result = controller.pullNextEligibleJobForExecution(predicate, testDispatcher)
        }

        // Advance so the first check runs and runner suspends on CompletableDeferred
        advanceUntilIdle()
        assertTrue(pullJob.isActive, "Runner should be suspended waiting for signal")

        // Signal job available via wakeUp (which calls signalJobAvailable)
        // Must run on managementDispatcher to access waitingRunners safely
        launch(testDispatcher) { controller.wakeUp() }
        advanceUntilIdle()

        assertTrue(pullJob.isCompleted, "Runner should have completed after signal")
        val job = assertNotNull(result)
        assertEquals("sig-1", job.id)
        verify { jobStorage.updateJobRunningState("sig-1", true) }
    }

    // -- Test 3: Multiple runners waiting --

    @Test
    fun `signalJobAvailable wakes all waiting runners`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val jobSpec1 = buildJobSpec("multi-1")
        val jobSpec2 = buildJobSpec("multi-2")
        val mockJob1 = createTestJob("multi-1")
        val mockJob2 = createTestJob("multi-2")

        // Both calls return empty first, then return one job each on re-check
        var callCount = 0
        every {
            jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any())
        } answers {
            callCount++
            when {
                callCount <= 2 -> emptyList()  // First check for each runner
                callCount == 3 -> listOf(jobSpec1)  // First runner's re-check after signal
                else -> listOf(jobSpec2)  // Second runner's re-check after signal
            }
        }
        every { jobStorage.getConstraintSpecs("multi-1") } returns emptyList()
        every { jobStorage.getConstraintSpecs("multi-2") } returns emptyList()
        every {
            jobInstantiator.instantiate("TestFactory", any(), any())
        } returnsMany listOf(mockJob1, mockJob2)

        var result1: Job? = null
        var result2: Job? = null

        val runner1 = launch {
            result1 = controller.pullNextEligibleJobForExecution(predicate, testDispatcher)
        }
        val runner2 = launch {
            result2 = controller.pullNextEligibleJobForExecution(predicate, testDispatcher)
        }

        // Let both runners perform initial check and suspend
        advanceUntilIdle()
        assertTrue(runner1.isActive, "Runner 1 should be suspended")
        assertTrue(runner2.isActive, "Runner 2 should be suspended")

        // Signal — should wake BOTH runners
        launch(testDispatcher) { controller.wakeUp() }
        advanceUntilIdle()

        assertTrue(runner1.isCompleted, "Runner 1 should have completed")
        assertTrue(runner2.isCompleted, "Runner 2 should have completed")
        assertNotNull(result1)
        assertNotNull(result2)
    }

    // -- Test 4: Signal before wait --

    @Test
    fun `signalJobAvailable when no runners waiting does not crash`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // Call wakeUp with no runners waiting — should be a no-op, no exceptions
        launch(testDispatcher) { controller.wakeUp() }
        advanceUntilIdle()

        // Also test via init() which calls signalJobAvailable
        controller.init()

        // No assertion needed — the test passes if no exception is thrown
    }

    // -- Test 5: Rapid submit-wake cycle --

    @Test
    fun `rapid submit and wake cycle delivers jobs correctly`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val iterations = 5

        val jobSpecs = (1..iterations).map { buildJobSpec("rapid-$it") }
        val mockJobs = (1..iterations).map { createTestJob("rapid-$it") }

        // Each pull: first check returns empty (runner suspends), after signal returns one job
        var pullCallCount = 0
        every {
            jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any())
        } answers {
            pullCallCount++
            // Odd calls (1st check per iteration) return empty; even calls (after signal) return job
            if (pullCallCount % 2 == 1) {
                emptyList()
            } else {
                val index = (pullCallCount / 2) - 1
                if (index < iterations) listOf(jobSpecs[index]) else emptyList()
            }
        }

        for (i in 0 until iterations) {
            every { jobStorage.getConstraintSpecs("rapid-${i + 1}") } returns emptyList()
        }

        var instantiateCallCount = 0
        every {
            jobInstantiator.instantiate("TestFactory", any(), any())
        } answers {
            mockJobs[instantiateCallCount++]
        }

        val results = mutableListOf<Job>()

        for (i in 0 until iterations) {
            val deferred = async {
                controller.pullNextEligibleJobForExecution(predicate, testDispatcher)
            }
            advanceUntilIdle()  // Runner suspends on CompletableDeferred

            // Signal to wake up
            launch(testDispatcher) { controller.wakeUp() }
            advanceUntilIdle()  // Runner resumes and returns job

            results.add(deferred.await())
        }

        assertEquals(iterations, results.size)
        for (i in 0 until iterations) {
            assertEquals("rapid-${i + 1}", results[i].id)
        }
    }

    // -- Test 6: Debouncer onEmpty called when no running jobs --

    @Test
    fun `pullNextEligibleJobForExecution publishes onEmpty via debouncer when no running jobs`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val jobSpec = buildJobSpec("empty-1")
        val mockJob = createTestJob("empty-1")

        // First check: empty (triggers debouncer). Second check after signal: has job.
        var callCount = 0
        every {
            jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any())
        } answers {
            callCount++
            if (callCount <= 1) emptyList() else listOf(jobSpec)
        }
        every { jobStorage.getConstraintSpecs("empty-1") } returns emptyList()
        every { jobInstantiator.instantiate("TestFactory", any(), any()) } returns mockJob

        val pullJob = launch {
            controller.pullNextEligibleJobForExecution(predicate, testDispatcher)
        }
        advanceUntilIdle()  // Runner checks, finds nothing, registers wait

        // Debouncer.publish should have been called with callback::onEmpty
        verify { debouncer.publish(any()) }

        // Wake to let the coroutine complete
        launch(testDispatcher) { controller.wakeUp() }
        advanceUntilIdle()
        assertTrue(pullJob.isCompleted)
    }

    // -- Test 7: Job with unmet constraints is skipped --

    @Test
    fun `pullNextEligibleJobForExecution skips jobs with unmet constraints`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val unmetJobSpec = buildJobSpec("unmet-1")
        val metJobSpec = buildJobSpec("met-1")
        val mockMetJob = createTestJob("met-1")

        val unmetConstraint = mockk<Constraint>(relaxed = true)
        every { unmetConstraint.isMet() } returns false
        val metConstraint = mockk<Constraint>(relaxed = true)
        every { metConstraint.isMet() } returns true

        // Return both jobs; first has unmet constraint, second has met constraint
        every {
            jobStorage.getPendingJobsWithNoDependenciesInCreatedOrder(any())
        } returns listOf(unmetJobSpec, metJobSpec)

        val unmetConstraintSpec = com.difft.android.chat.jobmanager.persistence.ConstraintSpec(
            "unmet-1", "network", false
        )
        every { jobStorage.getConstraintSpecs("unmet-1") } returns listOf(unmetConstraintSpec)
        every { jobStorage.getConstraintSpecs("met-1") } returns emptyList()
        every { constraintInstantiator.instantiate("network") } returns unmetConstraint
        every { jobInstantiator.instantiate("TestFactory", any(), any()) } returns mockMetJob

        val result = controller.pullNextEligibleJobForExecution(predicate, testDispatcher)

        assertEquals("met-1", result.id)
        // The unmet job should not be marked as running
        verify(exactly = 0) { jobStorage.updateJobRunningState("unmet-1", true) }
        verify { jobStorage.updateJobRunningState("met-1", true) }
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
        createTime: Long = 0L,
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
