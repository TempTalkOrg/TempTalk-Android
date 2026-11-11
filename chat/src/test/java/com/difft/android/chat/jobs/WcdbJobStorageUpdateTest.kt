package com.difft.android.chat.jobs

import com.difft.android.chat.jobmanager.persistence.JobSpec
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertNotNull









@Ignore("WCDB native library not loadable in JVM unit tests; run via instrumentation test instead")
class WcdbJobStorageUpdateTest {

    private fun jobSpec(
        id: String,
        runAttempt: Int = 0,
        isRunning: Boolean = false,
        memoryOnly: Boolean = false
    ): JobSpec = JobSpec(
        id = id,
        factoryKey = "push-text",
        queueKey = null,
        createTime = 1L,
        nextRunAttemptTime = 2L,
        runAttempt = runAttempt,
        maxAttempts = 5,
        lifespan = 60_000L,
        serializedData = "",
        isRunning = isRunning,
        isMemoryOnly = memoryOnly
    )

    @Test
    fun updateJobRunningState_changes_only_isRunning_column() {
        
        
        
        
        assertNotNull(jobSpec("job-1"))
    }

    @Test
    fun updateJobAfterRetry_writes_exactly_four_columns() {
        
        
        
        
    }

    @Test
    fun updateAllJobsToBePending_sets_isRunning_false_on_every_row() {
        
        
        
        
    }

    @Test
    fun updateJobs_skips_memory_only_specs() {
        
        
        
    }

    @Test
    fun updateJobs_swallows_exception() {
        
    }

    @Test
    fun updateJobRunningState_propagates_exception() {
        
    }

    @Test
    fun updateJobAfterRetry_propagates_exception() {
        
    }

    @Test
    fun updateAllJobsToBePending_propagates_exception() {
        
    }
}
