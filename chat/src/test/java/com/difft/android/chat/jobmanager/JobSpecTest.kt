package com.difft.android.chat.jobmanager

import com.difft.android.chat.jobmanager.persistence.ConstraintSpec
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobSpecTest {

    // region JobSpec constructor and field access

    @Test
    fun `constructor sets all fields`() {
        val spec = JobSpec(
            id = "job-1",
            factoryKey = "TestJobFactory",
            queueKey = "test-queue",
            createTime = 1000L,
            nextRunAttemptTime = 2000L,
            runAttempt = 3,
            maxAttempts = 5,
            lifespan = 60_000L,
            serializedData = "{}",
            isRunning = true,
            isMemoryOnly = false
        )

        assertEquals("job-1", spec.id)
        assertEquals("TestJobFactory", spec.factoryKey)
        assertEquals("test-queue", spec.queueKey)
        assertEquals(1000L, spec.createTime)
        assertEquals(2000L, spec.nextRunAttemptTime)
        assertEquals(3, spec.runAttempt)
        assertEquals(5, spec.maxAttempts)
        assertEquals(60_000L, spec.lifespan)
        assertEquals("{}", spec.serializedData)
        assertTrue(spec.isRunning)
        assertFalse(spec.isMemoryOnly)
    }

    // endregion

    // region queueKey nullable

    @Test
    fun `queueKey can be null`() {
        val spec = createJobSpec(queueKey = null)
        assertNull(spec.queueKey)
    }

    @Test
    fun `queueKey can be non-null`() {
        val spec = createJobSpec(queueKey = "my-queue")
        assertEquals("my-queue", spec.queueKey)
    }

    // endregion

    // region copy

    @Test
    fun `copy preserves all fields`() {
        val original = createJobSpec()
        val copy = original.copy()

        assertEquals(original, copy)
    }

    @Test
    fun `copy with single field changed`() {
        val original = createJobSpec(isRunning = false)
        val modified = original.copy(isRunning = true)

        assertTrue(modified.isRunning)
        assertEquals(original.id, modified.id)
        assertEquals(original.factoryKey, modified.factoryKey)
        assertEquals(original.queueKey, modified.queueKey)
        assertEquals(original.createTime, modified.createTime)
        assertEquals(original.nextRunAttemptTime, modified.nextRunAttemptTime)
        assertEquals(original.runAttempt, modified.runAttempt)
        assertEquals(original.maxAttempts, modified.maxAttempts)
        assertEquals(original.lifespan, modified.lifespan)
        assertEquals(original.serializedData, modified.serializedData)
        assertEquals(original.isMemoryOnly, modified.isMemoryOnly)
    }

    @Test
    fun `copy with multiple fields changed`() {
        val original = createJobSpec()
        val modified = original.copy(
            runAttempt = 10,
            nextRunAttemptTime = 99999L
        )

        assertEquals(10, modified.runAttempt)
        assertEquals(99999L, modified.nextRunAttemptTime)
        assertEquals(original.id, modified.id)
    }

    // endregion

    // region toString

    @Test
    fun `toString contains id with JOB prefix`() {
        val spec = createJobSpec(id = "abc-123")
        val str = spec.toString()

        assertTrue(str.contains("JOB::abc-123"), "toString should contain 'JOB::abc-123' but was: $str")
    }

    @Test
    fun `toString contains factoryKey`() {
        val spec = createJobSpec(factoryKey = "MyFactory")
        val str = spec.toString()

        assertTrue(str.contains("MyFactory"), "toString should contain 'MyFactory' but was: $str")
    }

    @Test
    fun `toString contains all key fields`() {
        val spec = createJobSpec(
            id = "test-id",
            factoryKey = "TestFactory",
            queueKey = "test-queue",
            isRunning = true,
            isMemoryOnly = true
        )
        val str = spec.toString()

        assertTrue(str.contains("JOB::test-id"))
        assertTrue(str.contains("TestFactory"))
        assertTrue(str.contains("test-queue"))
        assertTrue(str.contains("isRunning: true"))
        assertTrue(str.contains("memoryOnly: true"))
    }

    // endregion

    // region equals / hashCode (data class)

    @Test
    fun `equal specs have same hashCode`() {
        val spec1 = createJobSpec()
        val spec2 = createJobSpec()

        assertEquals(spec1, spec2)
        assertEquals(spec1.hashCode(), spec2.hashCode())
    }

    @Test
    fun `different id means not equal`() {
        val spec1 = createJobSpec(id = "id-1")
        val spec2 = createJobSpec(id = "id-2")

        assertNotEquals(spec1, spec2)
    }

    @Test
    fun `different isRunning means not equal`() {
        val spec1 = createJobSpec(isRunning = true)
        val spec2 = createJobSpec(isRunning = false)

        assertNotEquals(spec1, spec2)
    }

    // endregion

    // region ConstraintSpec

    @Test
    fun `ConstraintSpec constructor sets all fields`() {
        val spec = ConstraintSpec(
            jobSpecId = "job-1",
            factoryKey = "NetworkConstraintFactory",
            isMemoryOnly = false
        )

        assertEquals("job-1", spec.jobSpecId)
        assertEquals("NetworkConstraintFactory", spec.factoryKey)
        assertFalse(spec.isMemoryOnly)
    }

    @Test
    fun `ConstraintSpec toString contains JOB prefix`() {
        val spec = ConstraintSpec("job-1", "MyFactory", true)
        val str = spec.toString()

        assertTrue(str.contains("JOB::job-1"))
        assertTrue(str.contains("MyFactory"))
        assertTrue(str.contains("memoryOnly: true"))
    }

    @Test
    fun `ConstraintSpec equals works as data class`() {
        val spec1 = ConstraintSpec("job-1", "Factory", false)
        val spec2 = ConstraintSpec("job-1", "Factory", false)
        val spec3 = ConstraintSpec("job-2", "Factory", false)

        assertEquals(spec1, spec2)
        assertNotEquals(spec1, spec3)
    }

    @Test
    fun `ConstraintSpec copy works`() {
        val original = ConstraintSpec("job-1", "Factory", false)
        val copied = original.copy(isMemoryOnly = true)

        assertEquals("job-1", copied.jobSpecId)
        assertEquals("Factory", copied.factoryKey)
        assertTrue(copied.isMemoryOnly)
    }

    // endregion

    // region FullSpec

    @Test
    fun `FullSpec holds jobSpec and constraintSpecs`() {
        val jobSpec = createJobSpec()
        val constraints = listOf(
            ConstraintSpec("job-1", "NetworkFactory", false),
            ConstraintSpec("job-1", "ChargingFactory", false)
        )
        val fullSpec = FullSpec(jobSpec, constraints)

        assertEquals(jobSpec, fullSpec.jobSpec)
        assertEquals(2, fullSpec.constraintSpecs.size)
        assertEquals("NetworkFactory", fullSpec.constraintSpecs[0].factoryKey)
    }

    @Test
    fun `FullSpec isMemoryOnly delegates to jobSpec`() {
        val memoryOnlySpec = createJobSpec(isMemoryOnly = true)
        val persistedSpec = createJobSpec(isMemoryOnly = false)

        val fullMemory = FullSpec(memoryOnlySpec, emptyList())
        val fullPersisted = FullSpec(persistedSpec, emptyList())

        assertTrue(fullMemory.isMemoryOnly)
        assertFalse(fullPersisted.isMemoryOnly)
    }

    @Test
    fun `FullSpec with empty constraints`() {
        val fullSpec = FullSpec(createJobSpec(), emptyList())

        assertTrue(fullSpec.constraintSpecs.isEmpty())
    }

    @Test
    fun `FullSpec equals works as data class`() {
        val jobSpec = createJobSpec()
        val constraints = listOf(ConstraintSpec("job-1", "Factory", false))

        val full1 = FullSpec(jobSpec, constraints)
        val full2 = FullSpec(jobSpec, constraints)

        assertEquals(full1, full2)
        assertEquals(full1.hashCode(), full2.hashCode())
    }

    // endregion

    // region Helper

    private fun createJobSpec(
        id: String = "job-1",
        factoryKey: String = "TestJobFactory",
        queueKey: String? = "default-queue",
        createTime: Long = 1000L,
        nextRunAttemptTime: Long = 2000L,
        runAttempt: Int = 0,
        maxAttempts: Int = 3,
        lifespan: Long = -1L,
        serializedData: String = "{}",
        isRunning: Boolean = false,
        isMemoryOnly: Boolean = false
    ) = JobSpec(
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

    // endregion
}
