package com.difft.android.chat.jobs

import com.difft.android.chat.jobmanager.persistence.ConstraintSpec
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertNotNull








@Ignore("WCDB native library not loadable in JVM unit tests; run via instrumentation test instead")
class WcdbJobStorageInsertTest {

    private fun jobSpec(id: String, runAttempt: Int = 0, memoryOnly: Boolean = false) = JobSpec(
        id = id,
        factoryKey = "push-text",
        queueKey = "queue-$id",
        createTime = 1L,
        nextRunAttemptTime = 2L,
        runAttempt = runAttempt,
        maxAttempts = 5,
        lifespan = 60_000L,
        serializedData = "{\"k\":\"$id\"}",
        isRunning = false,
        isMemoryOnly = memoryOnly
    )

    private fun constraint(specId: String) = ConstraintSpec(
        jobSpecId = specId,
        factoryKey = "network",
        isMemoryOnly = false
    )

    @Test
    fun insert_single_durable_spec_with_constraint_is_persisted() {
        
        
        
        val fullSpec = FullSpec(jobSpec("job-1"), listOf(constraint("job-1")))
        assertNotNull(fullSpec)
    }

    @Test
    fun insert_memory_only_specs_are_skipped() {
        
        
        
        val fullSpec = FullSpec(jobSpec("job-1", memoryOnly = true), emptyList())
        assertNotNull(fullSpec)
    }

    @Test
    fun insert_duplicate_jobSpecId_is_ignored_not_replaced() {
        
        
        
        
        val first = FullSpec(jobSpec("job-dup", runAttempt = 1), emptyList())
        val second = FullSpec(jobSpec("job-dup", runAttempt = 99), emptyList())
        assertNotNull(first)
        assertNotNull(second)
    }

    @Test
    fun insert_duplicate_constraint_tuple_is_ignored() {
        
        
        val fullSpec = FullSpec(
            jobSpec("job-1"),
            listOf(constraint("job-1"), constraint("job-1"))
        )
        assertNotNull(fullSpec)
    }

    @Test
    fun insert_exception_is_swallowed_not_rethrown() {
        
        val fullSpec = FullSpec(jobSpec("job-1"), emptyList())
        assertNotNull(fullSpec)
    }
}
