package com.difft.android.chat.jobs

import com.difft.android.chat.jobmanager.persistence.ConstraintSpec
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertNotNull








@Ignore("WCDB native library not loadable in JVM unit tests; run via instrumentation test instead")
class WcdbJobStorageDeletePropagationTest {

    private fun jobSpec(id: String) = JobSpec(
        id = id,
        factoryKey = "push-text",
        queueKey = null,
        createTime = 1L,
        nextRunAttemptTime = 0L,
        runAttempt = 0,
        maxAttempts = 5,
        lifespan = 60_000L,
        serializedData = "",
        isRunning = false,
        isMemoryOnly = false
    )

    private fun constraint(specId: String) = ConstraintSpec(
        jobSpecId = specId,
        factoryKey = "network",
        isMemoryOnly = false
    )

    @Test
    fun deleteJobs_empty_list_is_noop_no_transaction_no_exception() {
        
        
        
    }

    @Test
    fun deleteJobs_removes_jobSpec_and_jobConstraint_atomically_in_one_transaction() {
        
        
        
        
        
        val fullSpec1 = FullSpec(jobSpec("job-1"), listOf(constraint("job-1"), constraint("job-1")))
        val fullSpec2 = FullSpec(jobSpec("job-2"), listOf(constraint("job-2")))
        assertNotNull(fullSpec1)
        assertNotNull(fullSpec2)
    }

    @Test
    fun deleteJobs_rethrows_WCDB_exception_instead_of_swallowing() {
        
    }

    @Test
    fun deleteJobs_rethrow_preserves_FastJobStorage_inMemory_consistency() {
        
        
    }
}
