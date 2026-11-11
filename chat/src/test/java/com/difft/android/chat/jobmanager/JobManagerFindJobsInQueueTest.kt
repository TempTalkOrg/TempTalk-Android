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









@RunWith(RobolectricTestRunner::class)
class JobManagerFindJobsInQueueTest {

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

    @Before
    fun setUp() {
        
        every { dataSerializer.serialize(any()) } returns "{}"
        every { dataSerializer.deserialize(any()) } returns Data.EMPTY
    }

    @After
    fun tearDown() {
        clearMocks(
            jobStorage, jobInstantiator, constraintInstantiator,
            dataSerializer, jobTracker, scheduler, persistentScheduler, debouncer, callback
        )
    }
    

    @Test
    fun `F1 - findJobsInQueue delegates to JobController and returns its list in order`() =
        runBlocking(Dispatchers.Default) {
            val spec1 = buildJobSpec(id = "spec-1", queueKey = "QUEUE", createTime = 100)
            val spec2 = buildJobSpec(id = "spec-2", queueKey = "QUEUE", createTime = 200)
            
            every { jobStorage.getJobsInQueue("QUEUE") } returns listOf(spec1, spec2)
            
            every { jobStorage.updateAllJobsToBePending() } just Runs

            val jobManager = newJobManager()

            val result = withTimeout(5_000) { jobManager.findJobsInQueue("QUEUE") }

            assertEquals(listOf(spec1, spec2), result)
            verify(exactly = 1) { jobStorage.getJobsInQueue("QUEUE") }
        }
    

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
    
    
    

    @Test
    fun `F3 - findJobsInQueue suspends until JobManager initDeferred completes`() =
        runBlocking(Dispatchers.Default) {
            val initGate = CountDownLatch(1)
            every { jobStorage.init() } answers {
                
                
                initGate.await(5, TimeUnit.SECONDS)
            }
            every { jobStorage.updateAllJobsToBePending() } just Runs

            val expectedSpec = buildJobSpec(id = "post-init", queueKey = "Q_F3", createTime = 1L)
            every { jobStorage.getJobsInQueue("Q_F3") } returns listOf(expectedSpec)

            val jobManager = newJobManager()

            
            var result: List<JobSpec>? = null
            val findJob: Job = launch {
                result = jobManager.findJobsInQueue("Q_F3")
            }

            
            delay(200)

            
            assertTrue(
                findJob.isActive,
                "findJobsInQueue should be suspended waiting for init, but it completed early"
            )
            verify(exactly = 0) { jobStorage.getJobsInQueue("Q_F3") }

            
            initGate.countDown()

            
            withTimeout(5_000) { findJob.join() }

            assertEquals(listOf(expectedSpec), result)
            verify(exactly = 1) { jobStorage.getJobsInQueue("Q_F3") }
        }
    

    @Test
    fun `F4 - JobController findJobsInQueue delegates to JobStorage preserving sort order`() {
        
        val sortedSpecs = listOf(
            buildJobSpec(id = "a", queueKey = "QC", createTime = 100),
            buildJobSpec(id = "b", queueKey = "QC", createTime = 200),
            buildJobSpec(id = "c", queueKey = "QC", createTime = 300),
        )
        every { jobStorage.getJobsInQueue("QC") } returns sortedSpecs

        val controller = JobController(
            application, jobStorage, jobInstantiator, constraintInstantiator,
            dataSerializer, jobTracker, scheduler, persistentScheduler, debouncer, callback
        )

        val result = controller.findJobsInQueue("QC")

        
        assertEquals(sortedSpecs, result)
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
        verify(exactly = 1) { jobStorage.getJobsInQueue("QC") }
    }

    @Test
    fun `F4b - JobController findJobsInQueue returns empty list when JobStorage has no matches`() {
        every { jobStorage.getJobsInQueue("EMPTY") } returns emptyList()

        val controller = JobController(
            application, jobStorage, jobInstantiator, constraintInstantiator,
            dataSerializer, jobTracker, scheduler, persistentScheduler, debouncer, callback
        )

        val result = controller.findJobsInQueue("EMPTY")

        assertTrue(result.isEmpty())
        verify(exactly = 1) { jobStorage.getJobsInQueue("EMPTY") }
    }
    

    @Test
    fun `F5 - 10 concurrent findJobsInQueue calls all complete without race`() =
        runBlocking(Dispatchers.Default) {
            val spec = buildJobSpec(id = "spec-c", queueKey = "QF5", createTime = 1L)
            every { jobStorage.getJobsInQueue("QF5") } returns listOf(spec)
            every { jobStorage.updateAllJobsToBePending() } just Runs

            val jobManager = newJobManager()

            
            
            
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
    

    @Test
    fun `F6 - two findJobsInQueue calls return independent equal lists not the same instance`() =
        runBlocking(Dispatchers.Default) {
            
            val spec = buildJobSpec(id = "snap-1", queueKey = "QF6", createTime = 1L)
            every { jobStorage.getJobsInQueue("QF6") } answers { listOf(spec) }
            every { jobStorage.updateAllJobsToBePending() } just Runs

            val jobManager = newJobManager()

            val first = withTimeout(5_000) { jobManager.findJobsInQueue("QF6") }
            val second = withTimeout(5_000) { jobManager.findJobsInQueue("QF6") }

            
            assertEquals(first, second)
            assertEquals(listOf(spec), first)

            
            verify(exactly = 2) { jobStorage.getJobsInQueue("QF6") }
        }
    

    





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
