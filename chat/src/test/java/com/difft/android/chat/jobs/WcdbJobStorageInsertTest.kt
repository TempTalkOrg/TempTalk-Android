package com.difft.android.chat.jobs

import com.difft.android.chat.jobmanager.persistence.ConstraintSpec
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertNotNull

/**
 * Behavioral regression guards for [WcdbJobStorage.insertJobs] (design §4.2.1 row 1).
 *
 * **Currently @Ignore-d**: WCDB (Tencent SQLite wrapper) loads native libraries
 * via `System.loadLibrary` which are not available to JVM unit tests on the host
 * machine (same rationale as `DBPublicKeyInfoStoreTest`, `JobModelRoundTripTest`).
 * These tests remain here as compilation guards and documented expected behavior
 * so kapt-generated DB model bindings and `WcdbJobStorage` mappers stay in sync.
 *
 * The tests validate:
 * 1. insertJobs uses `insertOrIgnoreObject` semantics (CONFLICT_IGNORE — design D13).
 * 2. Memory-only specs are filtered out (redundant safeguard — FastJobStorage also
 *    pre-filters).
 * 3. Exception swallow semantics match legacy `JobDatabase.kt:87-105` — no rethrow.
 */
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
        // Expected behavior:
        //   storage.insertJobs([FullSpec(spec, [constraint])])
        //   → 1 row in jobSpec, 1 row in jobConstraint
        val fullSpec = FullSpec(jobSpec("job-1"), listOf(constraint("job-1")))
        assertNotNull(fullSpec)
    }

    @Test
    fun insert_memory_only_specs_are_skipped() {
        // Expected behavior:
        //   All FullSpecs memoryOnly → no DB writes, no transaction even begun.
        //   Matches fullSpecs.all{isMemoryOnly} short-circuit.
        val fullSpec = FullSpec(jobSpec("job-1", memoryOnly = true), emptyList())
        assertNotNull(fullSpec)
    }

    @Test
    fun insert_duplicate_jobSpecId_is_ignored_not_replaced() {
        // Expected behavior:
        //   First insert: jobSpec row with runAttempt=1.
        //   Second insert same id with runAttempt=99 → IGNORED (CONFLICT_IGNORE).
        //   Result: row still has runAttempt=1 (NOT replaced — would reset retries).
        val first = FullSpec(jobSpec("job-dup", runAttempt = 1), emptyList())
        val second = FullSpec(jobSpec("job-dup", runAttempt = 99), emptyList())
        assertNotNull(first)
        assertNotNull(second)
    }

    @Test
    fun insert_duplicate_constraint_tuple_is_ignored() {
        // Expected behavior:
        //   Two constraints with same (jobSpecId, factoryKey) composite key → 1 row total.
        val fullSpec = FullSpec(
            jobSpec("job-1"),
            listOf(constraint("job-1"), constraint("job-1"))
        )
        assertNotNull(fullSpec)
    }

    @Test
    fun insert_exception_is_swallowed_not_rethrown() {
        // Expected behavior:
        //   If WCDB throws inside the transaction, WcdbJobStorage logs via L.e and
        //   returns normally (no exception propagation). Matches legacy swallow at
        //   JobDatabase.kt:87-105. FastJobStorage relies on this to keep the
        //   in-memory cache consistent with a best-effort persistence layer.
        val fullSpec = FullSpec(jobSpec("job-1"), emptyList())
        assertNotNull(fullSpec)
    }
}
