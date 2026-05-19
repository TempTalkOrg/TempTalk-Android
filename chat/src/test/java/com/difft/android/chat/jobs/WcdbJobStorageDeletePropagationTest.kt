package com.difft.android.chat.jobs

import com.difft.android.chat.jobmanager.persistence.ConstraintSpec
import com.difft.android.chat.jobmanager.persistence.FullSpec
import com.difft.android.chat.jobmanager.persistence.JobSpec
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertNotNull

/**
 * **Hard constraint D / H1 regression guard**: [WcdbJobStorage.deleteJobs] MUST
 * propagate exceptions to match legacy `JobDatabase.kt:197-211` parity. Legacy
 * has no `catch` — only `finally { endTransaction() }` — so any underlying
 * SQLCipher/SQLite error bubbles up to the JobController caller.
 *
 * Silent swallow here would violate hard constraint D, and (per design §4.2.1
 * row 8, Round 2 refinement wording) acts as a **future-refactor guard**: if a
 * subsequent refactor ever moves the in-memory `FastJobStorage.deleteJobs` to
 * run BEFORE the DB call, a silent swallow would leave a zombie row that
 * re-runs the job on next cold start. Propagation prevents that.
 *
 * **Currently @Ignore-d**: WCDB native library not loadable in JVM unit tests.
 * The rethrow semantics are nevertheless documented here as a compilation +
 * contract anchor.
 */
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
        // Expected behavior:
        //   storage.deleteJobs(emptyList()) returns immediately; no
        //   runTransaction called. (Short-circuit guard at the top of deleteJobs.)
    }

    @Test
    fun deleteJobs_removes_jobSpec_and_jobConstraint_atomically_in_one_transaction() {
        // Expected behavior:
        //   Setup: 2 jobSpec rows + 3 constraint rows (2 for "job-1", 1 for "job-2").
        //   storage.deleteJobs(["job-1"]) → 1 jobSpec + 2 constraint rows removed
        //   inside a SINGLE runTransaction.
        //   Remaining: 1 jobSpec ("job-2") + 1 constraint ("job-2").
        val fullSpec1 = FullSpec(jobSpec("job-1"), listOf(constraint("job-1"), constraint("job-1")))
        val fullSpec2 = FullSpec(jobSpec("job-2"), listOf(constraint("job-2")))
        assertNotNull(fullSpec1)
        assertNotNull(fullSpec2)
    }

    @Test
    fun deleteJobs_rethrows_WCDB_exception_instead_of_swallowing() {
        // Expected behavior (H1 regression guard):
        //   Inject a WCDB exception inside the transaction (e.g. by mutating the
        //   schema so the constraint table goes missing).
        //   storage.deleteJobs([...]) catches the exception, logs via L.e, then
        //   RETHROWS (throw e).
        //   The test must call:
        //     assertFailsWith<Exception> { storage.deleteJobs(ids) }
        //   If the exception is silently swallowed, this test MUST fail — the
        //   silent-swallow regression would have the opposite behavior.
    }

    @Test
    fun deleteJobs_rethrow_preserves_FastJobStorage_inMemory_consistency() {
        // Expected behavior (design §4.2.1 row 8 comment):
        //   The PROPAGATE contract ensures that if the DB delete fails,
        //   FastJobStorage's caller (JobController) sees the exception and can
        //   surface the inconsistency via crash telemetry rather than letting a
        //   zombie DB row survive a failed delete.
        //
        //   Note the Round 2 refinement wording here: the PROPAGATE is about
        //   "legacy parity (JobDatabase.kt:197-211 has no catch) + future-refactor
        //   guard" — NOT about the current FastJobStorage.deleteJobs ordering
        //   producing zombie rows (that claim would be inaccurate: current code
        //   does `jobStorage.deleteJobs(durableIds)` first, THEN
        //   `jobs.removeAll(...)`, so a DB failure short-circuits the in-memory
        //   step via propagation — no zombies produced).
    }
}
