package com.difft.android.messageserialization.db.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDB
import org.difft.app.database.models.PublicKeyInfoModel
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [DBPublicKeyInfoStore] against a real in-memory
 * WCDB instance.
 *
 * **Currently @Ignore-d**: WCDB (Tencent SQLite wrapper) loads native libraries
 * via `System.loadLibrary` which are not available to JVM unit tests on the
 * host machine. To run these tests reliably we either need (a) instrumentation
 * test setup (androidTest source set + emulator/device), or (b) a JVM-compatible
 * SQLite shim. Both are out of scope for the initial refactor; the tests remain
 * here as compilation guards and documented expected behavior.
 *
 * Until then, ORM semantics (primary-key pattern, `insertOrReplace`, `getValue`
 * aggregation) are covered by the facade-level tests in
 * `ConversationManagerImplTest` via mocks, and by manual smoke verification on
 * a debug build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Ignore("WCDB native library not loadable in JVM unit tests; run via instrumentation test instead")
class DBPublicKeyInfoStoreTest {

    private lateinit var wcdb: WCDB
    private lateinit var store: DBPublicKeyInfoStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        wcdb = TestWcdbFactory.createInMemoryWcdb(ctx)
        store = DBPublicKeyInfoStore(wcdb)
    }

    private fun model(
        uidValue: String,
        key: String = "key-$uidValue",
        reg: Int = 1,
        resetTime: Long = 0L
    ): PublicKeyInfoModel = PublicKeyInfoModel().apply {
        uid = uidValue
        identityKey = key
        registrationId = reg
        resetIdentityKeyTime = resetTime
    }

    @Test
    fun upsert_empty_list_is_noop() = runTest {
        store.upsert(emptyList())
        // No DB calls expected; hasAllUids on empty set is vacuously true.
        assertTrue(store.hasAllUids(emptyList()))
    }

    @Test
    fun upsert_single_row_visible_via_getForUids() = runTest {
        val m = model("u1", key = "key-u1", reg = 42, resetTime = 2000L)

        store.upsert(listOf(m))
        val result = store.getForUids(listOf("u1"))

        assertEquals(1, result.size)
        val read = result["u1"]!!
        assertEquals("u1", read.uid)
        assertEquals("key-u1", read.identityKey)
        assertEquals(42, read.registrationId)
        assertEquals(2000L, read.resetIdentityKeyTime)
    }

    @Test
    fun upsert_existing_uid_replaces_row() = runTest {
        // Pattern-B validator: verifies PK-based replacement.
        // Would fail if `databaseId` accidentally reintroduced as auto-inc PK.
        store.upsert(listOf(model("u1", key = "k1", reg = 1, resetTime = 100L)))
        store.upsert(listOf(model("u1", key = "k2", reg = 2, resetTime = 200L)))

        val result = store.getForUids(listOf("u1"))
        assertEquals(1, result.size, "Expected exactly 1 row after re-upsert; duplicates indicate broken PK")
        val read = result["u1"]!!
        assertEquals("k2", read.identityKey)
        assertEquals(2, read.registrationId)
        assertEquals(200L, read.resetIdentityKeyTime)
    }

    @Test
    fun upsert_100_row_batch_single_transaction() = runTest {
        val models = (1..100).map { model("u$it", key = "key-$it", reg = it) }
        store.upsert(models)

        val result = store.getForUids(models.map { it.uid })
        assertEquals(100, result.size)
        assertEquals("key-50", result["u50"]!!.identityKey)
    }

    @Test
    fun getForUids_empty_list_returns_empty_map() = runTest {
        val result = store.getForUids(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun getForUids_partial_hit_returns_only_cached() = runTest {
        store.upsert(listOf(model("u1"), model("u2")))

        val result = store.getForUids(listOf("u1", "u2", "u3"))
        assertEquals(2, result.size)
        assertTrue(result.containsKey("u1"))
        assertTrue(result.containsKey("u2"))
        assertNull(result["u3"])
    }

    @Test
    fun hasAllUids_empty_is_true_vacuously() = runTest {
        // Empty input MUST NOT hit the DB; result is vacuously true.
        assertTrue(store.hasAllUids(emptyList()))
    }

    @Test
    fun hasAllUids_all_cached_returns_true() = runTest {
        store.upsert(listOf(model("u1"), model("u2"), model("u3")))

        assertTrue(store.hasAllUids(listOf("u1", "u2", "u3")))
    }

    @Test
    fun hasAllUids_any_missing_returns_false() = runTest {
        store.upsert(listOf(model("u1"), model("u2")))

        assertFalse(store.hasAllUids(listOf("u1", "u2", "u3")))
        assertFalse(store.hasAllUids(listOf("u99")))
    }

    @Test
    fun deleteForUids_removes_specified_rows_only() = runTest {
        store.upsert(listOf(model("u1"), model("u2"), model("u3")))

        store.deleteForUids(listOf("u1", "u3"))

        val result = store.getForUids(listOf("u1", "u2", "u3"))
        assertEquals(1, result.size)
        assertTrue(result.containsKey("u2"))
    }
}
