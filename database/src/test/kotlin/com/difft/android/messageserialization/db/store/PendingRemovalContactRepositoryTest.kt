package com.difft.android.messageserialization.db.store

import com.google.gson.Gson
import com.tencent.wcdb.core.Database
import com.tencent.wcdb.core.Transaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.PendingRemovalContactModel
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [PendingRemovalContactRepository].
 *
 * - [PendingRemovalContactRepository.upsert] uses `insertOrReplaceObject` (WCDB REPLACE atomic
 *   upsert, uid primary key) — repeated upserts for the same uid carry the latest values; the
 *   table's PK REPLACE semantics dedupe to a single row.
 * - [PendingRemovalContactRepository.overwriteAll] runs `deleteObjects()` (no-arg = wipe) then
 *   `insertOrReplaceObjects(rows)` **inside one `db.runTransaction{}`**.
 * - A reconcile-built row whose snapshot carries a local remark serializes that remark into
 *   `snapshotJson` and round-trips back intact.
 *
 * **WCDB native-lib constraint (project precedent — see [DBPublicKeyInfoStoreTest])**: WCDB's
 * `Table`/`Database` extend `CppObject`, whose `<clinit>` runs `System.loadLibrary("WCDB")` —
 * unavailable to host JVM unit tests. MockK instantiates these return/mock types via Objenesis,
 * which triggers the native load → `UnsatisfiedLinkError`/`NoClassDefFoundError`. Therefore the
 * table-touching repo tests are `@Ignore`-d as compilation guards + documented behavior and run via
 * instrumentation. The remark-preservation invariant (pure gson round-trip, no WCDB) runs live.
 */
class PendingRemovalContactRepositoryTest {

    private val gson = Gson()

    // ---- remark round-trips through snapshotJson — pure, no WCDB ------------------------

    @Test
    fun `T20b reconcile-built row preserves remark through snapshotJson round-trip (MEDIUM-1)`() {
        // A reconcile-built row whose ContactorModel snapshot carries a local remark must keep
        // that remark in snapshotJson so the weak-table row retains it after the full overwrite.
        val snap = ContactorModel().apply { id = "c"; name = "Server"; remark = "我的备注" }
        val row = PendingRemovalContactModel().apply {
            uid = "c"; expireAt = 5L; reason = 0; deleteTime = 1L
            snapshotJson = gson.toJson(snap)
        }

        assertTrue(row.snapshotJson!!.contains("我的备注"), "remark must be serialized into snapshotJson")
        val back = gson.fromJson(row.snapshotJson, ContactorModel::class.java)
        assertEquals("我的备注", back.remark, "remark must round-trip back intact")
        assertEquals("c", back.id)
    }

    // =====================================================================================
    // @Ignore-d — touch WCDB Table/Database (CppObject → native lib at class-init). MockK's
    // relaxed mocks instantiate these via Objenesis, loading native WCDB (unavailable on host
    // JVM). Documented expected behavior preserved as compilation guards; run via instrumentation.
    // =====================================================================================

    // NOTE: WCDB/Database/Table mocks are created INSIDE the @Ignore-d test bodies only — never
    // as field initializers — because instantiating a CppObject-backed mock (Database/Table)
    // loads the native lib at field-init and would break even the live T20b above.

    @Test
    @Ignore("WCDB Table/Database (CppObject) loads native lib via MockK Objenesis; instrumentation only.")
    fun `T17 upsert issues insertOrReplaceObject with latest values on repeated upsert`() = runTest {
        val wcdb: WCDB = mockk(relaxed = true)
        val repo = PendingRemovalContactRepository(wcdb, gson)
        val uid = "u1"
        val snap1 = ContactorModel().apply { id = uid; name = "v1" }
        val snap2 = ContactorModel().apply { id = uid; name = "v2" }

        repo.upsert(uid, expireAt = 100L, reason = 0, deleteTime = 10L, snapshot = snap1)
        repo.upsert(uid, expireAt = 200L, reason = 1, deleteTime = 20L, snapshot = snap2)

        val rowSlot = mutableListOf<PendingRemovalContactModel>()
        verify(exactly = 2) { wcdb.pendingRemovalContact.insertOrReplaceObject(capture(rowSlot)) }
        assertEquals(uid, rowSlot[0].uid)
        assertEquals(uid, rowSlot[1].uid)
        val last = rowSlot[1]
        assertEquals(200L, last.expireAt)
        assertEquals(1, last.reason)
        assertEquals(20L, last.deleteTime)
        assertTrue(last.snapshotJson!!.contains("v2"))
    }

    @Test
    @Ignore("WCDB Table/Database (CppObject) loads native lib via MockK Objenesis; instrumentation only.")
    fun `T20 overwriteAll wipes table then bulk-inserts inside one transaction`() = runTest {
        val wcdb: WCDB = mockk(relaxed = true)
        val db: Database = mockk(relaxed = true)
        every { wcdb.db } returns db
        val txSlot = slot<Transaction>()
        every { db.runTransaction(capture(txSlot)) } answers {
            txSlot.captured.insideTransaction(mockk(relaxed = true))
        }
        val repo = PendingRemovalContactRepository(wcdb, gson)

        val rows = listOf(
            PendingRemovalContactModel().apply { uid = "c"; expireAt = 1L },
            PendingRemovalContactModel().apply { uid = "d"; expireAt = 2L },
        )

        repo.overwriteAll(rows)

        verify(exactly = 1) { db.runTransaction(any()) }
        verifyOrder {
            wcdb.pendingRemovalContact.deleteObjects()
            wcdb.pendingRemovalContact.insertOrReplaceObjects(rows)
        }
    }
}
