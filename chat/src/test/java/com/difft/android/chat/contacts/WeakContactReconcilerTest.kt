package com.difft.android.chat.contacts

import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.PendingRemovalContactRepository
import com.difft.android.network.BaseResponse
import com.difft.android.network.HttpService
import com.difft.android.network.responses.DeletedRecordDto
import com.google.gson.Gson
import com.tencent.wcdb.winq.Expression
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [WeakContactReconciler] — the weak-contact (delayed-removal) orchestration layer.
 *
 * All collaborators are MockK fakes; [WeakContactReconciler] is constructed via its real
 * constructor and the real entry methods are invoked — tests assert on the real orchestration,
 * never a re-implementation of it. `ContactorUtil` is an `object` → mocked via [mockkObject] so
 * the static `emitContactsUpdate` side-effect is verifiable and inert. [ServerTimeProvider] is the
 * real process singleton (reset with injected clocks in @Before to keep sibling tests deterministic;
 * reconcile itself no longer feeds the anchor — the network converter hook owns that).
 *
 * **WCDB native-lib constraint (project precedent — see [com.difft.android.chat.common
 * .ConversationManagerImplTest] and `DBPublicKeyInfoStoreTest`)**: WINQ `Expression`
 * (e.g. `DBContactorModel.id.eq(uid)`) extends `CppObject`, whose `<clinit>` runs
 * `System.loadLibrary("WCDB")` — unavailable to host JVM unit tests, throwing
 * `UnsatisfiedLinkError`. Tests whose production path constructs an `Expression`
 * (enterWeak/reconcile contactor reads/deletes) therefore CANNOT run on the JVM and are
 * `@Ignore`-d as compilation guards + documented expected behavior; they run via instrumentation.
 * Tests whose path touches NO `Expression` (removeWeak, removeNow, reconcile-gate) run live.
 *
 * Robolectric is the runner for Android framework availability; @Before injects deterministic clocks
 * into [ServerTimeProvider] so no real device clock is read by any collaborator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WeakContactReconcilerTest {

    private val pendingRepo: PendingRemovalContactRepository = mockk(relaxed = true)
    private val dbMessageStore: DBMessageStore = mockk(relaxed = true)
    private val httpService: HttpService = mockk()
    private val contactorCacheManager: ContactorCacheManager = mockk(relaxed = true)
    private val userManager: UserManager = mockk(relaxed = true)
    private val wcdb: WCDB = mockk(relaxed = true)
    private val gson = Gson()

    private lateinit var reconciler: WeakContactReconciler

    @Before
    fun setUp() {
        mockkObject(ContactorUtil)
        every { ContactorUtil.emitContactsUpdate(any()) } just Runs

        val userData = UserData().apply { microToken = "tok" }
        every { userManager.getUserData() } returns userData

        clearClockAnchor()

        reconciler = WeakContactReconciler(
            pendingRepo = pendingRepo,
            dbMessageStore = dbMessageStore,
            httpService = httpService,
            contactorCacheManager = contactorCacheManager,
            userManager = userManager,
            wcdb = wcdb,
            gson = gson,
        )
    }

    @After
    fun tearDown() {
        unmockkObject(ContactorUtil)
        clearMocks(pendingRepo, dbMessageStore, httpService, contactorCacheManager, userManager, wcdb)
        clearClockAnchor()
    }

    private fun clearClockAnchor() {
        ServerTimeProvider.resetForTest(wallClock = { 0L }, elapsedClock = { 0L })
    }

    private fun dto(
        uid: String,
        expireTime: Long = 1000L,
        reason: Int = 0,
        name: String? = "n-$uid",
        avatar: String? = null,
        deleteTime: Long = 0L,
    ) = DeletedRecordDto(
        uid = uid,
        reason = reason,
        name = name,
        avatar = avatar,
        deleteTime = deleteTime,
        expireTime = expireTime,
    )

    private fun successResp(data: List<DeletedRecordDto>, serverTimestamp: Long? = 5000L) =
        BaseResponse(
            ver = 1, status = 0, reason = null, data = data, serverTimestamp = serverTimestamp,
        )

    private fun failResp() =
        BaseResponse<List<DeletedRecordDto>>(ver = 1, status = 1, reason = "boom", data = null)

    /** HTTP 200 (status==0) but data==null — interface not deployed / parse failure / field mismatch. */
    private fun nullDataResp() =
        BaseResponse<List<DeletedRecordDto>>(ver = 1, status = 0, reason = null, data = null, serverTimestamp = 5000L)

    // =====================================================================================
    // LIVE TESTS — production path touches NO WINQ Expression (safe on host JVM).
    // =====================================================================================

    // ---- removeWeak = real removal (ct=1): drops placeholder AND deletes room ------------

    @Test
    fun `T3 removeWeak (real removal) drops placeholder AND deletes room, idempotent when absent`() = runTest {
        val uid = "u-rm"
        // Even when the uid is not in the weak table, removeWeak must not throw.
        coEvery { pendingRepo.remove(uid) } just Runs

        reconciler.removeWeak(uid)

        // 方案1: ct=1 = real removal (expiry / immediate / cross-device) → clear placeholder + room.
        coVerify(exactly = 1) { pendingRepo.remove(uid) }
        coVerify(exactly = 1) { dbMessageStore.removeRoomAndMessages(uid) }
        verify { contactorCacheManager.invalidateUser(uid) }
        verify { ContactorUtil.emitContactsUpdate(listOf(uid)) }
    }

    // ---- clearWeakOnFriendRestored = became a friend again: drops placeholder ONLY, keeps room ----

    @Test
    fun `T3b clearWeakOnFriendRestored drops placeholder ONLY (keeps room), idempotent when absent`() = runTest {
        val uid = "u-restore"
        coEvery { pendingRepo.remove(uid) } just Runs

        reconciler.clearWeakOnFriendRestored(uid)

        // Friend restored (directory action=0): drop the weak placeholder but KEEP the conversation.
        coVerify(exactly = 1) { pendingRepo.remove(uid) }
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages(uid) } // room preserved on restore
        verify { contactorCacheManager.invalidateUser(uid) }
        verify { ContactorUtil.emitContactsUpdate(listOf(uid)) }
    }

    // ---- reconcile gate (incomplete fetch → skip BEFORE any Expression) -----------------

    @Test
    fun `T5 reconcile skips all side effects on fetch failure`() = runTest {
        coEvery { httpService.fetchDeletedRecords(any()) } returns failResp()

        val ok = reconciler.reconcile("test")

        // Incomplete fetch → reconcile returns false so callers skip the mechanism-3 room sweep.
        assertFalse(ok)
        coVerify(exactly = 0) { pendingRepo.upsert(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { pendingRepo.remove(any()) }
        coVerify(exactly = 0) { pendingRepo.overwriteAll(any()) }
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages(any()) }
        // Gate returns before snapshotBeforeOverwrite() (the first step that would touch the table).
        coVerify(exactly = 0) { pendingRepo.snapshotBeforeOverwrite() }
    }

    @Test
    fun `T5b reconcile skips when fetch throws`() = runTest {
        coEvery { httpService.fetchDeletedRecords(any()) } throws RuntimeException("offline")

        val ok = reconciler.reconcile("test")

        assertFalse(ok) // fetch threw → false so callers skip the sweep
        coVerify(exactly = 0) { pendingRepo.overwriteAll(any()) }
        coVerify(exactly = 0) { pendingRepo.upsert(any(), any(), any(), any(), any()) }
    }

    // ---- 200 + data==null must NOT wipe the weak table / delete rooms -------------------
    // Server interface not yet deployed (or parse failure) returns status==0 with data==null.
    // The gate MUST treat data==null as "incomplete fetch" and return BEFORE touching the table,
    // otherwise resp.data.orEmpty() collapses latest to {}, so (before - latest.keys) iterates
    // every existing weak uid → removeWeakLocked (room delete) + overwriteAll(emptyList) wipes all.
    // This path returns at the gate before any WINQ Expression, so it is host-JVM safe (like T5).
    @Test
    fun `T5c reconcile skips all side effects when data is null (200 but undeployed)`() = runTest {
        coEvery { httpService.fetchDeletedRecords(any()) } returns nullDataResp()

        val ok = reconciler.reconcile("test")

        // data==null → false so ContactorUtil skips the mechanism-3 sweep (no false room deletions).
        assertFalse(ok)
        // No weak-table mutation and no conversation deletion — the whole point of the gate fix.
        coVerify(exactly = 0) { pendingRepo.overwriteAll(any()) }
        coVerify(exactly = 0) { pendingRepo.upsert(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { pendingRepo.remove(any()) }
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages(any()) }
        // Gate returns before snapshotBeforeOverwrite() — the first step that would read the table.
        coVerify(exactly = 0) { pendingRepo.snapshotBeforeOverwrite() }
    }

    // ---- dirty deletedRecords (null/blank uid) must be filtered, not crash --------
    // Repro for the cold-start NPE: gson fills the (nullable) uid with null when the server omits
    // the key, and `it.id = uid` in toContactorSnapshot then NPEs at setId. The reconcile filter
    // drops null/blank uids up front. With *all* records dirty, latest is empty and the post-gate
    // path constructs NO WINQ Expression (before is stubbed empty), so this runs live.
    @Test
    fun `T5d reconcile filters out null and blank uid records without crashing`() = runTest {
        val dirty = listOf(
            dto("ignored").copy(uid = null),  // the exact crash repro: gson-null uid
            dto("ignored").copy(uid = "  "),  // blank also dropped
            dto("ignored").copy(uid = ""),    // empty also dropped
        )
        coEvery { httpService.fetchDeletedRecords(any()) } returns successResp(dirty)
        coEvery { pendingRepo.snapshotBeforeOverwrite() } returns emptySet()

        // Must NOT throw (the bug was an NPE in toContactorSnapshot's `it.id = uid`).
        reconciler.reconcile("test")

        // Dirty records never reach enterWeak / snapshot creation.
        coVerify(exactly = 0) { pendingRepo.upsert(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages(any()) }
        // latest is empty after filtering → overwriteAll wipes with an empty row list.
        coVerify(exactly = 1) { pendingRepo.overwriteAll(emptyList()) }
    }

    @Test
    @Ignore("Valid record path reads contactor via WINQ Expression (native lib); instrumentation only.")
    fun `T5e reconcile skips the dirty record but still processes the valid one`() = runTest {
        // One dirty (null uid) + one valid. The dirty one is filtered; the valid one enters weak.
        val records = listOf(dto("valid"), dto("ignored").copy(uid = null))
        coEvery { httpService.fetchDeletedRecords(any()) } returns successResp(records)
        coEvery { pendingRepo.snapshotBeforeOverwrite() } returns emptySet()
        stubContactorPresence(present = emptySet())

        reconciler.reconcile("test")

        // Only the valid uid is backfilled; the null uid never reaches upsert.
        coVerify(exactly = 1) { pendingRepo.upsert("valid", any(), any(), any(), any()) }
        coVerify(exactly = 1) { pendingRepo.overwriteAll(match { it.size == 1 && it.first().uid == "valid" }) }
    }

    // ---- removeNow pessimistic strategy -------------------------------------------------

    @Test
    fun `T13 removeNow pessimistic - DELETE fails - no local delete, returns failure`() = runTest {
        val uid = "u-fail"
        coEvery { httpService.deleteDeletedRecord(uid, any()) } throws RuntimeException("offline")

        val result = reconciler.removeNow(uid)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages(uid) } // NOT deleted locally
        coVerify(exactly = 0) { pendingRepo.remove(uid) }
    }

    @Test
    fun `T14 removeNow pessimistic - DELETE succeeds - local removeWeak runs (real removal), returns success`() = runTest {
        val uid = "u-ok"
        coEvery { httpService.deleteDeletedRecord(uid, any()) } returns
            BaseResponse<Any>(ver = 1, status = 0, reason = null, data = null)

        val result = reconciler.removeNow(uid)

        assertTrue(result.isSuccess)
        // removeNow → removeWeak = real removal: drop the placeholder AND delete the room.
        coVerify(exactly = 1) { dbMessageStore.removeRoomAndMessages(uid) }
        coVerify(exactly = 1) { pendingRepo.remove(uid) }
        verify { ContactorUtil.emitContactsUpdate(listOf(uid)) }
    }

    // =====================================================================================
    // @Ignore-d TESTS — production path constructs a WINQ Expression (contactor read/delete)
    // which loads the native WCDB lib at class-init; not loadable in host JVM unit tests.
    // Run via instrumentation. Documented expected behavior preserved as compilation guards.
    // (Same precedent as ConversationManagerImplTest's Expression-touching @Ignore tests.)
    // =====================================================================================

    // ---- enterWeak ordering + remark preservation ---------------------------------------

    @Test
    @Ignore("WCDB Expression (DBContactorModel.id.eq remark read/delete) loads native lib; run as instrumentation test.")
    fun `T2 enterWeak writes weak table, deletes contactor + room (friend-era cleanup), preserves remark`() = runTest {
        val uid = "u-enter"
        // Existing contactor carries a local remark that must survive into the snapshot.
        val existing = ContactorModel().apply { id = uid; remark = "备注" }
        every { wcdb.contactor.getFirstObject(any<Expression>()) } returns existing

        val snapshot = ContactorModel().apply { id = uid; name = "Server Name" }
        reconciler.enterWeak(uid, expireAt = 9000L, reason = 0, deleteTime = 100L, snapshot = snapshot)

        // Best-effort remark copied from the live contactor into the snapshot (read before delete).
        assertEquals("备注", snapshot.remark)

        // enterWeak now owns the friend-era cleanup itself: write placeholder, then hard-delete the
        // contactor row + room. Driven by the always-delivered notify ct=0 / reconcile backfill, this
        // is the backstop for a dropped directory action (the room has no full-sync backstop).
        coVerifyOrder {
            pendingRepo.upsert(uid, 9000L, 0, 100L, snapshot)
            wcdb.contactor.deleteObjects(any<Expression>())
            dbMessageStore.removeRoomAndMessages(uid)
            contactorCacheManager.invalidateUser(uid)
            ContactorUtil.emitContactsUpdate(listOf(uid))
        }
    }

    // ---- reconcile three diff branches --------------------------------------------------

    @Test
    @Ignore("WCDB Expression (contactor read/delete in reconcile backfill) loads native lib; instrumentation only.")
    fun `T4 reconcile backfills new (deletes contactor + room) and vanished only drops placeholder (keeps room)`() = runTest {
        // before = {a, b}; latest = {a, c}.
        //   a: in before → "still" branch (refresh only; do NOT delete the coexisting contactor)
        //   c: not in before → enterWeakLocked backfill → DELETE contactor + room (friend-era cleanup)
        //   b: vanished → dropPlaceholderLocked → drop placeholder ONLY, KEEP the room (no
        //      contactor-presence check; cold-start contactor lag would misjudge a restored friend).
        coEvery { pendingRepo.snapshotBeforeOverwrite() } returns setOf("a", "b")
        coEvery { httpService.fetchDeletedRecords(any()) } returns successResp(listOf(dto("a"), dto("c")))
        stubContactorPresence(present = emptySet())

        reconciler.reconcile("test")

        // c (backfill) → enterWeakLocked: upsert + contactor delete + room delete (action backstop).
        coVerify { pendingRepo.upsert("c", any(), any(), any(), any()) }
        coVerify(exactly = 1) { dbMessageStore.removeRoomAndMessages("c") }

        // b (vanished) → placeholder dropped, room PRESERVED (room delete for a genuine removal is
        // handled by real-time ct=1 + the full-sync vanished-friend sweep, not here).
        coVerify { pendingRepo.remove("b") }
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages("b") }

        // Full overwrite always runs at the end.
        coVerify { pendingRepo.overwriteAll(any()) }
    }

    @Test
    fun `T4b reconcile vanished drops placeholder ONLY and keeps the room (no contactor-presence check)`() = runTest {
        // before = {x}; latest = {} ; x vanished. Change A: the vanished branch unconditionally drops
        // the placeholder and KEEPS the room — it no longer reads the contactor to distinguish
        // restored vs removed (cold-start contactor lag would misjudge a just-restored friend).
        // latest is empty → no backfill loop body and no toContactorSnapshot read → touches NO
        // Expression → runs live (same precedent as T5/T5c).
        coEvery { pendingRepo.snapshotBeforeOverwrite() } returns setOf("x")
        coEvery { httpService.fetchDeletedRecords(any()) } returns successResp(emptyList())

        val ok = reconciler.reconcile("test")

        assertTrue(ok) // complete response refreshed the weak table → sweep may proceed
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages("x") } // room always preserved here
        coVerify { pendingRepo.remove("x") }                               // placeholder dropped
        coVerify { pendingRepo.overwriteAll(any()) }
    }

    // ---- reconcile "still" branch is a no-op (contactor + weak coexistence is normal) ---------

    @Test
    @Ignore("Reconcile diff reads contactor via WINQ Expression (native lib); instrumentation only.")
    fun `T19 reconcile still-branch does NOT delete the contactor even when it coexists`() = runTest {
        // before = {g}; latest = {g} → "still" branch. Contactor still holds g (notify=25 arrived
        // before the directory action that hard-deletes it — a NORMAL transient state, not a ghost).
        coEvery { pendingRepo.snapshotBeforeOverwrite() } returns setOf("g")
        coEvery { httpService.fetchDeletedRecords(any()) } returns successResp(listOf(dto("g")))
        stubContactorPresence(present = setOf("g"))

        reconciler.reconcile("test")

        // The still-branch must NOT touch the contactor — friend removal is the directory action's job.
        verify(exactly = 0) { wcdb.contactor.deleteObjects(any<Expression>()) }
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages("g") }
        coVerify { pendingRepo.overwriteAll(any()) } // overwriteAll still refreshes expireAt/snapshot
    }

    @Test
    @Ignore("Reconcile diff reads contactor via WINQ Expression (native lib); instrumentation only.")
    fun `T19b reconcile still-branch is a no-op when contactor absent too`() = runTest {
        coEvery { pendingRepo.snapshotBeforeOverwrite() } returns setOf("g")
        coEvery { httpService.fetchDeletedRecords(any()) } returns successResp(listOf(dto("g")))
        stubContactorPresence(present = emptySet())

        reconciler.reconcile("test")

        verify(exactly = 0) { wcdb.contactor.deleteObjects(any<Expression>()) }
        coVerify { pendingRepo.overwriteAll(any()) }
    }

    /**
     * Stub `wcdb.contactor.getFirstObject(Expression)` so it returns a [ContactorModel] iff the
     * queried uid is in [present]. (Only reachable in @Ignore-d instrumentation runs — the
     * `any<Expression>()` matcher itself instantiates a native-backed Expression on the JVM.)
     */
    private fun stubContactorPresence(present: Set<String>) {
        every { wcdb.contactor.getFirstObject(any<Expression>()) } answers {
            val sql = firstArg<Expression>().toString()
            val hit = present.firstOrNull { sql.contains(it) }
            hit?.let { ContactorModel().apply { id = it } }
        }
    }
}
