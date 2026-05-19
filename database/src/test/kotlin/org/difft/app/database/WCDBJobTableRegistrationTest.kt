package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.messageserialization.db.store.TestWcdbFactory
import org.difft.app.database.models.DBJobConstraintModel
import org.difft.app.database.models.DBJobSpecModel
import org.difft.app.database.models.JobConstraintModel
import org.difft.app.database.models.JobSpecModel
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guardrail test asserting the new `job_spec` and `job_constraint` tables are
 * registered in [WCDB] with the **business-field PKs** demanded by design §4.1 /
 * hard constraint B — **no synthetic `_id INTEGER PRIMARY KEY AUTOINCREMENT`
 * column** may exist in either table.
 *
 * Addresses design §4.4 Risk R11: prevent accidental regression to the legacy
 * `_id AUTOINCREMENT` pattern if a future refactor copies another model's shape.
 *
 * **Strategy**: rather than peek at SQLite pragma internals, the guardrail uses
 * behavioral evidence — if a synthetic `_id AUTOINCREMENT` PK were present, an
 * `insertOrReplaceObject` round-trip keyed solely by the declared business PK
 * (`jobSpecId` for JobSpec, `(jobSpecId, factoryKey)` for JobConstraint) would
 * produce duplicate rows. Verifying that it produces exactly ONE row asserts the
 * business PK IS the PK.
 *
 * Also asserts table registration: both `job_spec` and `job_constraint` appear in
 * [WCDB.tablesMap] and are resolvable by name.
 *
 * **Currently @Ignore-d**: WCDB (Tencent SQLite wrapper) loads native libraries
 * via `System.loadLibrary` which are not available to JVM unit tests on the host
 * machine. Matches the precedent in
 * [com.difft.android.messageserialization.db.store.DBPublicKeyInfoStoreTest]
 * and [org.difft.app.database.models.JobModelRoundTripTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Ignore("WCDB native library not loadable in JVM unit tests; run via instrumentation test instead")
class WCDBJobTableRegistrationTest {

    private lateinit var wcdb: WCDB

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        wcdb = TestWcdbFactory.createInMemoryWcdb(ctx)
    }

    @Test
    fun tablesMap_contains_job_spec_and_job_constraint() {
        val keys = wcdb.tablesMap.keys
        assertTrue(keys.contains("job_spec"), "tablesMap must contain 'job_spec' — got: $keys")
        assertTrue(keys.contains("job_constraint"), "tablesMap must contain 'job_constraint' — got: $keys")
    }

    @Test
    fun jobSpec_table_handle_is_resolvable_by_name() {
        val table = wcdb.tablesMap["job_spec"]
        assertNotNull(table, "job_spec table handle must be resolvable from tablesMap")
        assertEquals("job_spec", table.tableName)
    }

    @Test
    fun jobConstraint_table_handle_is_resolvable_by_name() {
        val table = wcdb.tablesMap["job_constraint"]
        assertNotNull(table, "job_constraint table handle must be resolvable from tablesMap")
        assertEquals("job_constraint", table.tableName)
    }

    /**
     * Hard constraint B: `jobSpecId` alone is the primary key of `job_spec`.
     *
     * Behavioral assertion: two consecutive `insertOrReplaceObject` calls with the
     * SAME `jobSpecId` but different payload MUST collapse to a single row with the
     * second payload winning. If a synthetic `_id AUTOINCREMENT` PK were accidentally
     * reintroduced, the two calls would produce TWO rows (different `_id` values),
     * not one.
     */
    @Test
    fun jobSpec_has_no_synthetic_id_column_behavioral() {
        val first = JobSpecModel().apply {
            jobSpecId = "job-dup"
            factoryKey = "push-text"
            queueKey = null
            createTime = 1L
            nextRunAttemptTime = 2L
            runAttempt = 1
            maxAttempts = 5
            lifespan = 60_000L
            serializedData = null
            isRunning = false
        }
        val second = JobSpecModel().apply {
            jobSpecId = "job-dup"
            factoryKey = "push-text"
            queueKey = null
            createTime = 1L
            nextRunAttemptTime = 2L
            runAttempt = 99
            maxAttempts = 5
            lifespan = 60_000L
            serializedData = null
            isRunning = false
        }

        wcdb.jobSpec.insertOrReplaceObject(first)
        wcdb.jobSpec.insertOrReplaceObject(second)

        val rows = wcdb.jobSpec.getAllObjects()
        assertEquals(
            1, rows.size,
            "Exactly 1 row expected after re-upsert; duplicates indicate a synthetic _id AUTOINCREMENT PK regression"
        )
        assertEquals(99, rows[0].runAttempt, "second payload should win on PK collision")
    }

    /**
     * Hard constraint B: `(jobSpecId, factoryKey)` is the composite primary key of
     * `job_constraint`.
     *
     * Behavioral assertion: two consecutive `insertOrReplaceObject` calls with the
     * SAME `(jobSpecId, factoryKey)` tuple MUST collapse to a single row. If a
     * synthetic `_id AUTOINCREMENT` PK were accidentally reintroduced, the two
     * calls would produce TWO rows.
     */
    @Test
    fun jobConstraint_has_no_synthetic_id_column_behavioral() {
        val c1 = JobConstraintModel().apply {
            jobSpecId = "job-1"
            factoryKey = "network"
        }
        val c2 = JobConstraintModel().apply {
            jobSpecId = "job-1"
            factoryKey = "network"
        }

        wcdb.jobConstraint.insertOrReplaceObject(c1)
        wcdb.jobConstraint.insertOrReplaceObject(c2)

        val rows = wcdb.jobConstraint.getAllObjects()
        assertEquals(
            1, rows.size,
            "Exactly 1 row expected for duplicate (jobSpecId, factoryKey); duplicates indicate broken composite PK"
        )
    }

    /**
     * Sanity check: the binding INSTANCE objects for both models are non-null. This
     * confirms the WCDB annotation processor successfully generated
     * `DBJobSpecModel` and `DBJobConstraintModel` from the POJOs.
     */
    @Test
    fun generated_bindings_are_non_null() {
        assertNotNull(DBJobSpecModel.INSTANCE, "DBJobSpecModel.INSTANCE must be generated by WCDB compiler")
        assertNotNull(DBJobConstraintModel.INSTANCE, "DBJobConstraintModel.INSTANCE must be generated by WCDB compiler")
    }
}
