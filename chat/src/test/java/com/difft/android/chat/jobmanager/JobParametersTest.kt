package com.difft.android.chat.jobmanager

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobParametersTest {

    // region Builder defaults

    @Test
    fun `default builder generates non-empty id`() {
        val params = Job.Parameters.Builder().build()
        assertNotNull(params.id)
        assertTrue(params.id.isNotEmpty(), "Default id should be non-empty UUID")
    }

    @Test
    fun `default builder sets createTime to approximately now`() {
        val before = System.currentTimeMillis()
        val params = Job.Parameters.Builder().build()
        val after = System.currentTimeMillis()

        assertTrue(
            params.createTime in before..after,
            "createTime ${params.createTime} should be between $before and $after"
        )
    }

    @Test
    fun `default builder sets lifespan to IMMORTAL`() {
        val params = Job.Parameters.Builder().build()
        assertEquals(Job.Parameters.IMMORTAL, params.lifespan)
    }

    @Test
    fun `default builder sets maxAttempts to 1`() {
        val params = Job.Parameters.Builder().build()
        assertEquals(1, params.maxAttempts)
    }

    @Test
    fun `default builder sets queue to null`() {
        val params = Job.Parameters.Builder().build()
        assertNull(params.queue)
    }

    @Test
    fun `default builder sets constraintKeys to empty`() {
        val params = Job.Parameters.Builder().build()
        assertTrue(params.constraintKeys.isEmpty())
    }

    @Test
    fun `default builder sets memoryOnly to false`() {
        val params = Job.Parameters.Builder().build()
        assertFalse(params.isMemoryOnly)
    }

    @Test
    fun `default builder sets maxInstancesForFactory to UNLIMITED`() {
        val params = Job.Parameters.Builder().build()
        assertEquals(Job.Parameters.UNLIMITED, params.maxInstancesForFactory)
    }

    @Test
    fun `default builder sets maxInstancesForQueue to UNLIMITED`() {
        val params = Job.Parameters.Builder().build()
        assertEquals(Job.Parameters.UNLIMITED, params.maxInstancesForQueue)
    }

    // endregion

    // region Builder with explicit id

    @Test
    fun `builder with explicit id uses that id`() {
        val params = Job.Parameters.Builder("my-id").build()
        assertEquals("my-id", params.id)
    }

    // endregion

    // region Builder setters

    @Test
    fun `setQueue sets queue key`() {
        val params = Job.Parameters.Builder()
            .setQueue("my-queue")
            .build()

        assertEquals("my-queue", params.queue)
    }

    @Test
    fun `setQueue with null clears queue`() {
        val params = Job.Parameters.Builder()
            .setQueue("my-queue")
            .setQueue(null)
            .build()

        assertNull(params.queue)
    }

    @Test
    fun `setLifespan sets lifespan`() {
        val params = Job.Parameters.Builder()
            .setLifespan(30_000L)
            .build()

        assertEquals(30_000L, params.lifespan)
    }

    @Test
    fun `setMaxAttempts sets maxAttempts`() {
        val params = Job.Parameters.Builder()
            .setMaxAttempts(5)
            .build()

        assertEquals(5, params.maxAttempts)
    }

    @Test
    fun `setMaxInstancesForFactory sets value`() {
        val params = Job.Parameters.Builder()
            .setMaxInstancesForFactory(3)
            .build()

        assertEquals(3, params.maxInstancesForFactory)
    }

    @Test
    fun `setMaxInstancesForQueue sets value`() {
        val params = Job.Parameters.Builder()
            .setMaxInstancesForQueue(2)
            .build()

        assertEquals(2, params.maxInstancesForQueue)
    }

    @Test
    fun `addConstraint adds single constraint`() {
        val params = Job.Parameters.Builder()
            .addConstraint("NETWORK")
            .build()

        assertEquals(listOf("NETWORK"), params.constraintKeys)
    }

    @Test
    fun `addConstraint accumulates multiple constraints`() {
        val params = Job.Parameters.Builder()
            .addConstraint("NETWORK")
            .addConstraint("CHARGING")
            .build()

        assertEquals(listOf("NETWORK", "CHARGING"), params.constraintKeys)
    }

    @Test
    fun `setConstraints replaces all constraints`() {
        val params = Job.Parameters.Builder()
            .addConstraint("OLD")
            .setConstraints(listOf("NEW1", "NEW2"))
            .build()

        assertEquals(listOf("NEW1", "NEW2"), params.constraintKeys)
    }

    @Test
    fun `setMemoryOnly sets memoryOnly to true`() {
        val params = Job.Parameters.Builder()
            .setMemoryOnly(true)
            .build()

        assertTrue(params.isMemoryOnly)
    }

    @Test
    fun `setMemoryOnly sets memoryOnly to false`() {
        val params = Job.Parameters.Builder()
            .setMemoryOnly(true)
            .setMemoryOnly(false)
            .build()

        assertFalse(params.isMemoryOnly)
    }

    // endregion

    // region Constants

    @Test
    fun `IMMORTAL constant is -1`() {
        assertEquals(-1L, Job.Parameters.IMMORTAL)
    }

    @Test
    fun `UNLIMITED constant is -1`() {
        assertEquals(-1, Job.Parameters.UNLIMITED)
    }

    @Test
    fun `MIGRATION_QUEUE_KEY is MIGRATION`() {
        assertEquals("MIGRATION", Job.Parameters.MIGRATION_QUEUE_KEY)
    }

    // endregion

    // region toBuilder roundtrip

    @Test
    fun `toBuilder preserves all fields`() {
        val original = Job.Parameters.Builder("test-id")
            .setCreateTime(12345L)
            .setLifespan(60_000L)
            .setMaxAttempts(10)
            .setMaxInstancesForFactory(5)
            .setMaxInstancesForQueue(3)
            .setQueue("my-queue")
            .addConstraint("NETWORK")
            .addConstraint("CHARGING")
            .setMemoryOnly(true)
            .build()

        val rebuilt = original.toBuilder().build()

        assertEquals(original.id, rebuilt.id)
        assertEquals(original.createTime, rebuilt.createTime)
        assertEquals(original.lifespan, rebuilt.lifespan)
        assertEquals(original.maxAttempts, rebuilt.maxAttempts)
        assertEquals(original.maxInstancesForFactory, rebuilt.maxInstancesForFactory)
        assertEquals(original.maxInstancesForQueue, rebuilt.maxInstancesForQueue)
        assertEquals(original.queue, rebuilt.queue)
        assertEquals(original.constraintKeys, rebuilt.constraintKeys)
        assertEquals(original.isMemoryOnly, rebuilt.isMemoryOnly)
    }

    @Test
    fun `toBuilder allows modification`() {
        val original = Job.Parameters.Builder("test-id")
            .setQueue("queue-1")
            .setMaxAttempts(3)
            .build()

        val modified = original.toBuilder()
            .setQueue("queue-2")
            .setMaxAttempts(5)
            .build()

        assertEquals("test-id", modified.id)
        assertEquals("queue-2", modified.queue)
        assertEquals(5, modified.maxAttempts)
    }

    // endregion

    // region Builder chaining

    @Test
    fun `all builder setters return builder for chaining`() {
        // This test verifies the fluent API compiles and works
        val params = Job.Parameters.Builder("chain-test")
            .setCreateTime(1000L)
            .setLifespan(2000L)
            .setMaxAttempts(3)
            .setMaxInstancesForFactory(4)
            .setMaxInstancesForQueue(5)
            .setQueue("queue")
            .addConstraint("NETWORK")
            .setMemoryOnly(true)
            .build()

        assertEquals("chain-test", params.id)
        assertEquals(1000L, params.createTime)
        assertEquals(2000L, params.lifespan)
        assertEquals(3, params.maxAttempts)
        assertEquals(4, params.maxInstancesForFactory)
        assertEquals(5, params.maxInstancesForQueue)
        assertEquals("queue", params.queue)
        assertEquals(listOf("NETWORK"), params.constraintKeys)
        assertTrue(params.isMemoryOnly)
    }

    // endregion
}
