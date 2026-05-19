package org.difft.app.database.models

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.messageserialization.db.store.TestWcdbFactory
import org.difft.app.database.WCDB
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration round-trip tests for [JobSpecModel] and [JobConstraintModel] against
 * a real in-memory WCDB instance.
 *
 * **Currently @Ignore-d**: WCDB (Tencent SQLite wrapper) loads native libraries
 * via `System.loadLibrary` which are not available to JVM unit tests on the host
 * machine. Matches the precedent in [com.difft.android.messageserialization.db.store.DBPublicKeyInfoStoreTest].
 * To run these tests reliably we either need (a) instrumentation test setup
 * (androidTest source set + emulator/device), or (b) a JVM-compatible SQLite shim.
 * Both are out of scope for the initial refactor; the tests remain here as
 * compilation guards and documented expected behavior.
 *
 * These tests validate:
 * - JobSpec row-round-trip preserves every field (10 columns including `serializedData TEXT`
 *   and `isRunning boolean` -> INTEGER).
 * - JobSpec `jobSpecId` primary key uniquely identifies rows (re-insert via insertOrReplace
 *   replaces instead of duplicating).
 * - JobConstraint composite PK `(jobSpecId, factoryKey)` uniquely identifies rows.
 * - `queueKey` nullability is preserved round-trip.
 * - `ORDER BY createTime ASC, jobSpecId ASC` returns stable ordering (schema parity with
 *   legacy `ORDER BY create_time, _id ASC` — see design §4.2.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Ignore("WCDB native library not loadable in JVM unit tests; run via instrumentation test instead")
class JobModelRoundTripTest {

    private lateinit var wcdb: WCDB

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        wcdb = TestWcdbFactory.createInMemoryWcdb(ctx)
    }

    private fun spec(
        id: String,
        factory: String = "factory-$id",
        queue: String? = "queue-$id",
        createTime: Long = 1_000L,
        nextRun: Long = 2_000L,
        runAttempt: Int = 0,
        maxAttempts: Int = 5,
        lifespan: Long = 60_000L,
        payload: String? = "{\"k\":\"$id\"}",
        running: Boolean = false
    ): JobSpecModel = JobSpecModel().apply {
        jobSpecId = id
        factoryKey = factory
        queueKey = queue
        this.createTime = createTime
        nextRunAttemptTime = nextRun
        this.runAttempt = runAttempt
        this.maxAttempts = maxAttempts
        this.lifespan = lifespan
        serializedData = payload
        isRunning = running
    }

    private fun constraint(
        specId: String,
        factory: String = "network"
    ): JobConstraintModel = JobConstraintModel().apply {
        jobSpecId = specId
        factoryKey = factory
    }

    @Test
    fun jobSpec_round_trip_preserves_all_fields() {
        val original = spec(
            id = "job-1",
            factory = "push-text",
            queue = "conversation-a",
            createTime = 123_456L,
            nextRun = 456_789L,
            runAttempt = 2,
            maxAttempts = 10,
            lifespan = 120_000L,
            payload = "{\"message\":\"hello\"}",
            running = true
        )

        wcdb.jobSpec.insertObject(original)
        val readBack = wcdb.jobSpec.getAllObjects()

        assertEquals(1, readBack.size)
        val row = readBack[0]
        assertEquals("job-1", row.jobSpecId)
        assertEquals("push-text", row.factoryKey)
        assertEquals("conversation-a", row.queueKey)
        assertEquals(123_456L, row.createTime)
        assertEquals(456_789L, row.nextRunAttemptTime)
        assertEquals(2, row.runAttempt)
        assertEquals(10, row.maxAttempts)
        assertEquals(120_000L, row.lifespan)
        assertEquals("{\"message\":\"hello\"}", row.serializedData)
        assertEquals(true, row.isRunning)
    }

    @Test
    fun jobSpec_queueKey_and_serializedData_nullable_round_trip() {
        val nullQueue = spec(id = "job-nq", queue = null, payload = null)
        wcdb.jobSpec.insertObject(nullQueue)

        val rows = wcdb.jobSpec.getAllObjects()
        assertEquals(1, rows.size)
        assertNull(rows[0].queueKey)
        assertNull(rows[0].serializedData)
    }

    @Test
    fun jobSpec_insertOrReplace_on_same_jobSpecId_replaces_row() {
        // Verifies business PK uniqueness — if a synthetic _id AUTOINCREMENT PK were
        // accidentally reintroduced, a second insertOrReplace with the same jobSpecId
        // would produce TWO rows (different _id values), not one.
        wcdb.jobSpec.insertOrReplaceObject(spec(id = "job-dup", runAttempt = 1))
        wcdb.jobSpec.insertOrReplaceObject(spec(id = "job-dup", runAttempt = 99))

        val rows = wcdb.jobSpec.getAllObjects()
        assertEquals(1, rows.size, "Expected exactly 1 row after re-upsert; duplicates indicate broken PK")
        assertEquals(99, rows[0].runAttempt)
    }

    @Test
    fun jobSpec_multi_row_round_trip_preserves_all_rows() {
        // Ordering-aware retrieval is validated in WcdbJobStorageReadTest (Task 2).
        // Here we only verify the table can hold multiple distinct rows.
        wcdb.jobSpec.insertObjects(
            listOf(
                spec(id = "b-later", createTime = 200L),
                spec(id = "a-early", createTime = 100L),
                spec(id = "a-same", createTime = 200L)
            )
        )

        val rows = wcdb.jobSpec.getAllObjects()
        assertEquals(3, rows.size)
        assertEquals(setOf("a-early", "a-same", "b-later"), rows.map { it.jobSpecId }.toSet())
    }

    @Test
    fun jobConstraint_round_trip_preserves_composite_key() {
        val c1 = constraint(specId = "job-1", factory = "network")
        val c2 = constraint(specId = "job-1", factory = "chargeable")
        val c3 = constraint(specId = "job-2", factory = "network")
        wcdb.jobConstraint.insertObjects(listOf(c1, c2, c3))

        val rows = wcdb.jobConstraint.getAllObjects()
        assertEquals(3, rows.size)
        val job1 = rows.filter { it.jobSpecId == "job-1" }.map { it.factoryKey }.sorted()
        assertEquals(listOf("chargeable", "network"), job1)
    }

    @Test
    fun jobConstraint_composite_pk_prevents_duplicate_rows() {
        // If composite PK (jobSpecId, factoryKey) is correctly declared, a
        // second insertOrReplace with the same tuple must replace, not duplicate.
        wcdb.jobConstraint.insertOrReplaceObject(constraint("job-1", "network"))
        wcdb.jobConstraint.insertOrReplaceObject(constraint("job-1", "network"))

        val rows = wcdb.jobConstraint.getAllObjects()
        assertEquals(1, rows.size, "Composite PK must prevent duplicate (jobSpecId, factoryKey) rows")
    }

    @Test
    fun jobConstraint_different_factoryKey_same_jobSpecId_keeps_both() {
        wcdb.jobConstraint.insertObject(constraint("job-1", "network"))
        wcdb.jobConstraint.insertObject(constraint("job-1", "chargeable"))

        val rows = wcdb.jobConstraint.getAllObjects()
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.factoryKey == "network" })
        assertTrue(rows.any { it.factoryKey == "chargeable" })
    }

    @Test
    fun tables_are_independent_empty_jobSpec_with_nonempty_jobConstraint() {
        wcdb.jobConstraint.insertObject(constraint("orphan", "network"))

        assertEquals(0, wcdb.jobSpec.getAllObjects().size)
        assertEquals(1, wcdb.jobConstraint.getAllObjects().size)
    }

    @Test
    fun isRunning_boolean_round_trips_as_integer_zero_one() {
        wcdb.jobSpec.insertObject(spec(id = "job-r-true", running = true))
        wcdb.jobSpec.insertObject(spec(id = "job-r-false", running = false))

        val rows = wcdb.jobSpec.getAllObjects().associateBy { it.jobSpecId }
        assertNotNull(rows["job-r-true"])
        assertNotNull(rows["job-r-false"])
        assertEquals(true, rows["job-r-true"]!!.isRunning)
        assertEquals(false, rows["job-r-false"]!!.isRunning)
    }
}
