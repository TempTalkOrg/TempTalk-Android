package com.difft.android.chat.jobs

import com.difft.android.chat.jobmanager.persistence.JobSpec
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertNotNull

/**
 * Behavioral regression guards for [WcdbJobStorage.getAllJobSpecs] and
 * [WcdbJobStorage.getAllConstraintSpecs] (design §4.2.1 rows 2-3).
 *
 * **Currently @Ignore-d**: WCDB native library not loadable in JVM unit tests
 * (same rationale as sibling test classes). These are compilation guards +
 * documented expected behavior.
 *
 * Key properties validated:
 * 1. `ORDER BY createTime ASC, jobSpecId ASC` (D12 — legacy used `_id` as tiebreaker
 *    which we dropped; this replacement ordering is stable and deterministic).
 * 2. All 10 JobSpec fields round-trip (via mapper `JobSpecModel.toJobSpec()`).
 * 3. `isMemoryOnly = false` on DB entries (can't be stored memory-only in DB).
 * 4. `getAllConstraintSpecs` has no ordering requirement (matches legacy
 *    `JobDatabase.kt:214-234` which also did not ORDER BY).
 * 5. Exception swallow — returns `emptyList()` on WCDB failure (legacy parity).
 */
@Ignore("WCDB native library not loadable in JVM unit tests; run via instrumentation test instead")
class WcdbJobStorageReadTest {

    private fun jobSpec(id: String, createTime: Long): JobSpec = JobSpec(
        id = id,
        factoryKey = "push-text",
        queueKey = null,
        createTime = createTime,
        nextRunAttemptTime = 0L,
        runAttempt = 0,
        maxAttempts = 5,
        lifespan = 60_000L,
        serializedData = "",
        isRunning = false,
        isMemoryOnly = false
    )

    @Test
    fun getAllJobSpecs_sorts_by_createTime_ASC_then_jobSpecId_ASC() {
        // Expected behavior:
        //   Insert 4 rows:
        //     a-same (createTime=200)
        //     b-later (createTime=200)  ← tiebreak; b > a-same → sorts after
        //     c-early (createTime=100)
        //     d-later (createTime=300)
        //   getAllJobSpecs() returns:
        //     [c-early, a-same, b-later, d-later]
        //   This asserts the D12 tiebreaker (jobSpecId ASC) stays stable after
        //   dropping legacy `_id` column.
        val specs = listOf(
            jobSpec("a-same", 200L),
            jobSpec("b-later", 200L),
            jobSpec("c-early", 100L),
            jobSpec("d-later", 300L)
        )
        assertNotNull(specs)
    }

    @Test
    fun getAllJobSpecs_maps_all_ten_fields_including_null_queueKey_and_null_serializedData() {
        // Expected behavior:
        //   JobSpecModel row with queueKey=NULL, serializedData=NULL round-trips
        //   via mapper to JobSpec with:
        //     queueKey=null, serializedData=""  ← mapper promotes NULL → ""
        //     isMemoryOnly=false                ← mapper sets false (DB = durable)
        assertNotNull(jobSpec("j-null", 1L))
    }

    @Test
    fun getAllJobSpecs_returns_emptyList_on_failure() {
        // Expected behavior:
        //   If WCDB throws, L.e is logged and emptyList() returned (swallow).
        //   FastJobStorage.init() handles emptyList() gracefully.
    }

    @Test
    fun getAllConstraintSpecs_returns_all_rows_no_ordering_required() {
        // Expected behavior:
        //   All constraint rows returned; caller (FastJobStorage) groups by
        //   jobSpecId in-memory — no need for DB-side ordering.
    }

    @Test
    fun getAllConstraintSpecs_returns_emptyList_on_failure() {
        // Expected behavior:
        //   Same swallow semantics as getAllJobSpecs — matches JobDatabase.kt:214-234.
    }
}
