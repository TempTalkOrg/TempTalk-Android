package com.difft.android.chat.contacts

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








@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WeakContactReconcilerTest {

    private val pendingRepo: PendingRemovalContactRepository = mockk(relaxed = true)
    private val dbMessageStore: DBMessageStore = mockk(relaxed = true)
    private val httpService: HttpService = mockk()
    private val contactorCacheManager: ContactorCacheManager = mockk(relaxed = true)
    private val wcdb: WCDB = mockk(relaxed = true)
    private val gson = Gson()

    private lateinit var reconciler: WeakContactReconciler

    @Before
    fun setUp() {
        mockkObject(ContactorUtil)
        every { ContactorUtil.emitContactsUpdate(any()) } just Runs

        clearClockAnchor()

        reconciler = WeakContactReconciler(
            pendingRepo = pendingRepo,
            dbMessageStore = dbMessageStore,
            httpService = httpService,
            contactorCacheManager = contactorCacheManager,
            wcdb = wcdb,
            gson = gson,
        )
    }

    @After
    fun tearDown() {
        unmockkObject(ContactorUtil)
        clearMocks(pendingRepo, dbMessageStore, httpService, contactorCacheManager, wcdb)
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

    
    private fun nullDataResp() =
        BaseResponse<List<DeletedRecordDto>>(ver = 1, status = 0, reason = null, data = null, serverTimestamp = 5000L)

    
    
    

    

    @Test
    fun `T3 removeWeak (real removal) drops placeholder AND deletes room, idempotent when absent`() = runTest {
        val uid = "u-rm"
        
        coEvery { pendingRepo.remove(uid) } just Runs

        reconciler.removeWeak(uid)

        
        coVerify(exactly = 1) { pendingRepo.remove(uid) }
        coVerify(exactly = 1) { dbMessageStore.removeRoomAndMessages(uid) }
        verify { contactorCacheManager.invalidateUser(uid) }
        verify { ContactorUtil.emitContactsUpdate(listOf(uid)) }
    }

    

    @Test
    fun `T3b clearWeakOnFriendRestored drops placeholder ONLY (keeps room), idempotent when absent`() = runTest {
        val uid = "u-restore"
        coEvery { pendingRepo.remove(uid) } just Runs

        reconciler.clearWeakOnFriendRestored(uid)

        
        coVerify(exactly = 1) { pendingRepo.remove(uid) }
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages(uid) } 
        verify { contactorCacheManager.invalidateUser(uid) }
        verify { ContactorUtil.emitContactsUpdate(listOf(uid)) }
    }

    

    @Test
    fun `T5 reconcile skips all side effects on fetch failure`() = runTest {
        coEvery { httpService.fetchDeletedRecords() } returns failResp()

        val ok = reconciler.reconcile("test")

        
        assertFalse(ok)
        coVerify(exactly = 0) { pendingRepo.upsert(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { pendingRepo.remove(any()) }
        coVerify(exactly = 0) { pendingRepo.overwriteAll(any()) }
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages(any()) }
        
        coVerify(exactly = 0) { pendingRepo.snapshotBeforeOverwrite() }
    }

    @Test
    fun `T5b reconcile skips when fetch throws`() = runTest {
        coEvery { httpService.fetchDeletedRecords() } throws RuntimeException("offline")

        val ok = reconciler.reconcile("test")

        assertFalse(ok) 
        coVerify(exactly = 0) { pendingRepo.overwriteAll(any()) }
        coVerify(exactly = 0) { pendingRepo.upsert(any(), any(), any(), any(), any()) }
    }

    
    
    @Test
    fun `T5c reconcile skips all side effects when data is null (200 but undeployed)`() = runTest {
        coEvery { httpService.fetchDeletedRecords() } returns nullDataResp()

        val ok = reconciler.reconcile("test")

        
        assertFalse(ok)
        
        coVerify(exactly = 0) { pendingRepo.overwriteAll(any()) }
        coVerify(exactly = 0) { pendingRepo.upsert(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { pendingRepo.remove(any()) }
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages(any()) }
        
        coVerify(exactly = 0) { pendingRepo.snapshotBeforeOverwrite() }
    }

    
    
    @Test
    fun `T5d reconcile filters out null and blank uid records without crashing`() = runTest {
        val dirty = listOf(
            dto("ignored").copy(uid = null),  
            dto("ignored").copy(uid = "  "),  
            dto("ignored").copy(uid = ""),    
        )
        coEvery { httpService.fetchDeletedRecords() } returns successResp(dirty)
        coEvery { pendingRepo.snapshotBeforeOverwrite() } returns emptySet()

        
        reconciler.reconcile("test")

        
        coVerify(exactly = 0) { pendingRepo.upsert(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages(any()) }
        
        coVerify(exactly = 1) { pendingRepo.overwriteAll(emptyList()) }
    }

    @Test
    @Ignore("Valid record path reads contactor via WINQ Expression (native lib); instrumentation only.")
    fun `T5e reconcile skips the dirty record but still processes the valid one`() = runTest {
        
        val records = listOf(dto("valid"), dto("ignored").copy(uid = null))
        coEvery { httpService.fetchDeletedRecords() } returns successResp(records)
        coEvery { pendingRepo.snapshotBeforeOverwrite() } returns emptySet()
        stubContactorPresence(present = emptySet())

        reconciler.reconcile("test")

        
        coVerify(exactly = 1) { pendingRepo.upsert("valid", any(), any(), any(), any()) }
        coVerify(exactly = 1) { pendingRepo.overwriteAll(match { it.size == 1 && it.first().uid == "valid" }) }
    }

    

    @Test
    fun `T13 removeNow pessimistic - DELETE fails - no local delete, returns failure`() = runTest {
        val uid = "u-fail"
        coEvery { httpService.deleteDeletedRecord(uid) } throws RuntimeException("offline")

        val result = reconciler.removeNow(uid)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages(uid) } 
        coVerify(exactly = 0) { pendingRepo.remove(uid) }
    }

    @Test
    fun `T14 removeNow pessimistic - DELETE succeeds - local removeWeak runs (real removal), returns success`() = runTest {
        val uid = "u-ok"
        coEvery { httpService.deleteDeletedRecord(uid) } returns
            BaseResponse<Any>(ver = 1, status = 0, reason = null, data = null)

        val result = reconciler.removeNow(uid)

        assertTrue(result.isSuccess)
        
        coVerify(exactly = 1) { dbMessageStore.removeRoomAndMessages(uid) }
        coVerify(exactly = 1) { pendingRepo.remove(uid) }
        verify { ContactorUtil.emitContactsUpdate(listOf(uid)) }
    }

    
    
    
    
    
    

    

    @Test
    @Ignore("WCDB Expression (DBContactorModel.id.eq remark read/delete) loads native lib; run as instrumentation test.")
    fun `T2 enterWeak writes weak table, deletes contactor + room (friend-era cleanup), preserves remark`() = runTest {
        val uid = "u-enter"
        
        val existing = ContactorModel().apply { id = uid; remark = "备注" }
        every { wcdb.contactor.getFirstObject(any<Expression>()) } returns existing

        val snapshot = ContactorModel().apply { id = uid; name = "Server Name" }
        reconciler.enterWeak(uid, expireAt = 9000L, reason = 0, deleteTime = 100L, snapshot = snapshot)

        
        assertEquals("备注", snapshot.remark)

        
        
        coVerifyOrder {
            pendingRepo.upsert(uid, 9000L, 0, 100L, snapshot)
            wcdb.contactor.deleteObjects(any<Expression>())
            dbMessageStore.removeRoomAndMessages(uid)
            contactorCacheManager.invalidateUser(uid)
            ContactorUtil.emitContactsUpdate(listOf(uid))
        }
    }

    

    @Test
    @Ignore("WCDB Expression (contactor read/delete in reconcile backfill) loads native lib; instrumentation only.")
    fun `T4 reconcile backfills new (deletes contactor + room) and vanished only drops placeholder (keeps room)`() = runTest {
        
        
        
        
        
        coEvery { pendingRepo.snapshotBeforeOverwrite() } returns setOf("a", "b")
        coEvery { httpService.fetchDeletedRecords() } returns successResp(listOf(dto("a"), dto("c")))
        stubContactorPresence(present = emptySet())

        reconciler.reconcile("test")

        
        coVerify { pendingRepo.upsert("c", any(), any(), any(), any()) }
        coVerify(exactly = 1) { dbMessageStore.removeRoomAndMessages("c") }

        
        
        coVerify { pendingRepo.remove("b") }
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages("b") }

        
        coVerify { pendingRepo.overwriteAll(any()) }
    }

    @Test
    fun `T4b reconcile vanished drops placeholder ONLY and keeps the room (no contactor-presence check)`() = runTest {
        
        
        coEvery { pendingRepo.snapshotBeforeOverwrite() } returns setOf("x")
        coEvery { httpService.fetchDeletedRecords() } returns successResp(emptyList())

        val ok = reconciler.reconcile("test")

        assertTrue(ok) 
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages("x") } 
        coVerify { pendingRepo.remove("x") }                               
        coVerify { pendingRepo.overwriteAll(any()) }
    }

    

    @Test
    @Ignore("Reconcile diff reads contactor via WINQ Expression (native lib); instrumentation only.")
    fun `T19 reconcile still-branch does NOT delete the contactor even when it coexists`() = runTest {
        
        
        coEvery { pendingRepo.snapshotBeforeOverwrite() } returns setOf("g")
        coEvery { httpService.fetchDeletedRecords() } returns successResp(listOf(dto("g")))
        stubContactorPresence(present = setOf("g"))

        reconciler.reconcile("test")

        
        verify(exactly = 0) { wcdb.contactor.deleteObjects(any<Expression>()) }
        coVerify(exactly = 0) { dbMessageStore.removeRoomAndMessages("g") }
        coVerify { pendingRepo.overwriteAll(any()) } 
    }

    @Test
    @Ignore("Reconcile diff reads contactor via WINQ Expression (native lib); instrumentation only.")
    fun `T19b reconcile still-branch is a no-op when contactor absent too`() = runTest {
        coEvery { pendingRepo.snapshotBeforeOverwrite() } returns setOf("g")
        coEvery { httpService.fetchDeletedRecords() } returns successResp(listOf(dto("g")))
        stubContactorPresence(present = emptySet())

        reconciler.reconcile("test")

        verify(exactly = 0) { wcdb.contactor.deleteObjects(any<Expression>()) }
        coVerify { pendingRepo.overwriteAll(any()) }
    }

    




    private fun stubContactorPresence(present: Set<String>) {
        every { wcdb.contactor.getFirstObject(any<Expression>()) } answers {
            val sql = firstArg<Expression>().toString()
            val hit = present.firstOrNull { sql.contains(it) }
            hit?.let { ContactorModel().apply { id = it } }
        }
    }
}
