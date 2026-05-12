package com.difft.android.chat.jobmanager

import com.difft.android.chat.jobmanager.persistence.ConstraintSpec
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import com.difft.android.chat.jobs.FastJobStorage
import com.difft.android.chat.jobs.WcdbJobStorage
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FastJobStorageTest {

    private val jobStorage = mockk<WcdbJobStorage>(relaxed = true)
    private lateinit var storage: FastJobStorage

    @Before
    fun setUp() {
        every { jobStorage.getAllJobSpecs() } returns emptyList()
        every { jobStorage.getAllConstraintSpecs() } returns emptyList()
        storage = FastJobStorage(jobStorage)
    }

    @After
    fun tearDown() {
        clearMocks(jobStorage)
    }

    // -- init() --

    @Test
    fun `init loads jobs and constraints from database into memory`() {
        val jobSpec = buildJobSpec(id = "job-1")
        val constraintSpec = ConstraintSpec(jobSpecId = "job-1", factoryKey = "network", isMemoryOnly = false)
        every { jobStorage.getAllJobSpecs() } returns listOf(jobSpec)
        every { jobStorage.getAllConstraintSpecs() } returns listOf(constraintSpec)

        storage.init()

        assertEquals(jobSpec, storage.getJobSpec("job-1"))
        assertEquals(listOf(constraintSpec), storage.getConstraintSpecs("job-1"))
    }

    @Test
    fun `init with empty database results in empty storage`() {
        storage.init()

        assertTrue(storage.getAllJobSpecs().isEmpty())
        assertEquals(emptyList(), storage.getAllConstraintSpecs())
    }

    @Test
    fun `init handles database exception gracefully`() {
        every { jobStorage.getAllJobSpecs() } throws RuntimeException("DB error")

        storage.init()

        assertTrue(storage.getAllJobSpecs().isEmpty())
    }

    // -- insertJobs() --

    @Test
    fun `insertJobs adds jobs to memory and persists durable jobs to DB`() {
        storage.init()
        val jobSpec = buildJobSpec(id = "job-1", isMemoryOnly = false)
        val fullSpec = FullSpec(jobSpec, emptyList())

        storage.insertJobs(listOf(fullSpec))

        assertEquals(jobSpec, storage.getJobSpec("job-1"))
        verify(exactly = 1) { jobStorage.insertJobs(listOf(fullSpec)) }
    }

    @Test
    fun `insertJobs skips DB persistence for memoryOnly jobs`() {
        storage.init()
        val jobSpec = buildJobSpec(id = "mem-1", isMemoryOnly = true)
        val fullSpec = FullSpec(jobSpec, emptyList())

        storage.insertJobs(listOf(fullSpec))

        assertEquals(jobSpec, storage.getJobSpec("mem-1"))
        verify(exactly = 0) { jobStorage.insertJobs(any()) }
    }

    @Test
    fun `insertJobs with mixed durable and memoryOnly persists only durable`() {
        storage.init()
        val durableSpec = buildJobSpec(id = "durable-1", isMemoryOnly = false)
        val memorySpec = buildJobSpec(id = "memory-1", isMemoryOnly = true)
        val durableFullSpec = FullSpec(durableSpec, emptyList())
        val memoryFullSpec = FullSpec(memorySpec, emptyList())

        storage.insertJobs(listOf(durableFullSpec, memoryFullSpec))

        assertEquals(durableSpec, storage.getJobSpec("durable-1"))
        assertEquals(memorySpec, storage.getJobSpec("memory-1"))
        verify(exactly = 1) { jobStorage.insertJobs(listOf(durableFullSpec)) }
    }

    @Test
    fun `insertJobs stores constraint specs in memory`() {
        storage.init()
        val jobSpec = buildJobSpec(id = "job-c1")
        val constraint = ConstraintSpec("job-c1", "network", false)
        val fullSpec = FullSpec(jobSpec, listOf(constraint))

        storage.insertJobs(listOf(fullSpec))

        assertEquals(listOf(constraint), storage.getConstraintSpecs("job-c1"))
    }

    // -- getJobSpec() --

    @Test
    fun `getJobSpec returns matching job`() {
        storage.init()
        val jobSpec = buildJobSpec(id = "find-me")
        storage.insertJobs(listOf(FullSpec(jobSpec, emptyList())))

        assertEquals(jobSpec, storage.getJobSpec("find-me"))
    }

    @Test
    fun `getJobSpec returns null for unknown id`() {
        storage.init()

        assertNull(storage.getJobSpec("nonexistent"))
    }

    // -- getPendingJobsWithNoDependenciesInCreatedOrder() --

    @Test
    fun `getPending returns empty list when storage is empty`() {
        storage.init()

        val result = storage.getPendingJobsWithNoDependenciesInCreatedOrder(1000L)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getPending returns single eligible job`() {
        storage.init()
        val job = buildJobSpec(id = "job-1", createTime = 100, nextRunAttemptTime = 0, isRunning = false)
        storage.insertJobs(listOf(FullSpec(job, emptyList())))

        val result = storage.getPendingJobsWithNoDependenciesInCreatedOrder(1000L)

        assertEquals(listOf(job), result)
    }

    @Test
    fun `getPending excludes running jobs`() {
        storage.init()
        val job = buildJobSpec(id = "job-1", isRunning = true, nextRunAttemptTime = 0)
        storage.insertJobs(listOf(FullSpec(job, emptyList())))

        val result = storage.getPendingJobsWithNoDependenciesInCreatedOrder(1000L)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getPending excludes jobs with future nextRunAttemptTime`() {
        storage.init()
        val job = buildJobSpec(id = "job-1", nextRunAttemptTime = 9999L, isRunning = false)
        storage.insertJobs(listOf(FullSpec(job, emptyList())))

        val result = storage.getPendingJobsWithNoDependenciesInCreatedOrder(1000L)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getPending returns only first job per queue ordered by createTime`() {
        storage.init()
        val job1 = buildJobSpec(id = "j1", queueKey = "q1", createTime = 100, nextRunAttemptTime = 0)
        val job2 = buildJobSpec(id = "j2", queueKey = "q1", createTime = 200, nextRunAttemptTime = 0)
        storage.insertJobs(listOf(FullSpec(job1, emptyList()), FullSpec(job2, emptyList())))

        val result = storage.getPendingJobsWithNoDependenciesInCreatedOrder(1000L)

        assertEquals(1, result.size)
        assertEquals("j1", result[0].id)
    }

    @Test
    fun `getPending returns one job per distinct queue`() {
        storage.init()
        val jobA = buildJobSpec(id = "a1", queueKey = "qA", createTime = 100, nextRunAttemptTime = 0)
        val jobB = buildJobSpec(id = "b1", queueKey = "qB", createTime = 200, nextRunAttemptTime = 0)
        storage.insertJobs(listOf(FullSpec(jobA, emptyList()), FullSpec(jobB, emptyList())))

        val result = storage.getPendingJobsWithNoDependenciesInCreatedOrder(1000L)

        assertEquals(2, result.size)
        assertEquals(setOf("a1", "b1"), result.map { it.id }.toSet())
    }

    @Test
    fun `getPending treats null queueKey as own queue using id`() {
        storage.init()
        val job1 = buildJobSpec(id = "no-q-1", queueKey = null, createTime = 100, nextRunAttemptTime = 0)
        val job2 = buildJobSpec(id = "no-q-2", queueKey = null, createTime = 200, nextRunAttemptTime = 0)
        storage.insertJobs(listOf(FullSpec(job1, emptyList()), FullSpec(job2, emptyList())))

        val result = storage.getPendingJobsWithNoDependenciesInCreatedOrder(1000L)

        assertEquals(2, result.size)
        assertEquals(setOf("no-q-1", "no-q-2"), result.map { it.id }.toSet())
    }

    @Test
    fun `getPending migration job takes priority over all others`() {
        storage.init()
        val regular = buildJobSpec(id = "r1", queueKey = "regular", createTime = 50, nextRunAttemptTime = 0)
        val migration = buildJobSpec(
            id = "m1",
            queueKey = Job.Parameters.MIGRATION_QUEUE_KEY,
            createTime = 100,
            nextRunAttemptTime = 0
        )
        storage.insertJobs(listOf(FullSpec(regular, emptyList()), FullSpec(migration, emptyList())))

        val result = storage.getPendingJobsWithNoDependenciesInCreatedOrder(1000L)

        assertEquals(1, result.size)
        assertEquals("m1", result[0].id)
    }

    @Test
    fun `getPending migration job running blocks all other jobs`() {
        storage.init()
        val regular = buildJobSpec(id = "r1", queueKey = "regular", createTime = 50, nextRunAttemptTime = 0)
        val migration = buildJobSpec(
            id = "m1",
            queueKey = Job.Parameters.MIGRATION_QUEUE_KEY,
            createTime = 100,
            nextRunAttemptTime = 0,
            isRunning = true
        )
        storage.insertJobs(listOf(FullSpec(regular, emptyList()), FullSpec(migration, emptyList())))

        val result = storage.getPendingJobsWithNoDependenciesInCreatedOrder(1000L)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getPending migration job with future nextRunAttemptTime blocks all`() {
        storage.init()
        val regular = buildJobSpec(id = "r1", queueKey = "regular", createTime = 50, nextRunAttemptTime = 0)
        val migration = buildJobSpec(
            id = "m1",
            queueKey = Job.Parameters.MIGRATION_QUEUE_KEY,
            createTime = 100,
            nextRunAttemptTime = 9999L,
            isRunning = false
        )
        storage.insertJobs(listOf(FullSpec(regular, emptyList()), FullSpec(migration, emptyList())))

        val result = storage.getPendingJobsWithNoDependenciesInCreatedOrder(1000L)

        assertTrue(result.isEmpty())
    }

    // -- updateJobRunningState() --

    @Test
    fun `updateJobRunningState updates memory and DB for durable job`() {
        storage.init()
        val job = buildJobSpec(id = "job-1", isRunning = false, isMemoryOnly = false)
        storage.insertJobs(listOf(FullSpec(job, emptyList())))

        storage.updateJobRunningState("job-1", true)

        assertTrue(storage.getJobSpec("job-1")!!.isRunning)
        verify { jobStorage.updateJobRunningState("job-1", true) }
    }

    @Test
    fun `updateJobRunningState skips DB for memoryOnly job`() {
        storage.init()
        val job = buildJobSpec(id = "mem-1", isRunning = false, isMemoryOnly = true)
        storage.insertJobs(listOf(FullSpec(job, emptyList())))

        storage.updateJobRunningState("mem-1", true)

        assertTrue(storage.getJobSpec("mem-1")!!.isRunning)
        verify(exactly = 0) { jobStorage.updateJobRunningState("mem-1", any()) }
    }

    // -- updateJobAfterRetry() --

    @Test
    fun `updateJobAfterRetry updates all fields in memory`() {
        storage.init()
        val job = buildJobSpec(id = "retry-1", runAttempt = 0, nextRunAttemptTime = 0)
        storage.insertJobs(listOf(FullSpec(job, emptyList())))

        storage.updateJobAfterRetry("retry-1", false, 1, 5000L, "new-data")

        val updated = storage.getJobSpec("retry-1")!!
        assertFalse(updated.isRunning)
        assertEquals(1, updated.runAttempt)
        assertEquals(5000L, updated.nextRunAttemptTime)
        assertEquals("new-data", updated.serializedData)
    }

    // -- deleteJobs() --

    @Test
    fun `deleteJobs removes from memory and DB`() {
        storage.init()
        val job = buildJobSpec(id = "del-1")
        val constraint = ConstraintSpec("del-1", "network", false)
        storage.insertJobs(listOf(FullSpec(job, listOf(constraint))))

        storage.deleteJobs(listOf("del-1"))

        assertNull(storage.getJobSpec("del-1"))
        assertTrue(storage.getConstraintSpecs("del-1").isEmpty())
        verify { jobStorage.deleteJobs(listOf("del-1")) }
    }

    @Test
    fun `deleteJobs skips DB for memoryOnly jobs`() {
        storage.init()
        val job = buildJobSpec(id = "mem-del", isMemoryOnly = true)
        storage.insertJobs(listOf(FullSpec(job, emptyList())))

        storage.deleteJobs(listOf("mem-del"))

        assertNull(storage.getJobSpec("mem-del"))
        verify(exactly = 0) { jobStorage.deleteJobs(any()) }
    }

    // -- getAllJobSpecs() --

    @Test
    fun `getAllJobSpecs returns copy of jobs list`() {
        storage.init()
        val job1 = buildJobSpec(id = "j1")
        val job2 = buildJobSpec(id = "j2")
        storage.insertJobs(listOf(FullSpec(job1, emptyList()), FullSpec(job2, emptyList())))

        val result = storage.getAllJobSpecs()

        assertEquals(2, result.size)
        assertEquals(setOf("j1", "j2"), result.map { it.id }.toSet())
    }

    // -- Helper --

    private fun buildJobSpec(
        id: String = "test-job",
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
