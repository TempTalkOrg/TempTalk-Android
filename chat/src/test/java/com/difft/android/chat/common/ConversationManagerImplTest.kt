package com.difft.android.chat.common

import com.difft.android.base.utils.globalServices

import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.group.GroupUtil
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.websocket.api.PublicKeyUpdateResult
import com.difft.android.websocket.api.messages.GetPublicKeysReq
import com.difft.android.websocket.api.messages.GetPublicKeysResp
import com.difft.android.websocket.api.messages.PublicKeyInfo
import difft.android.messageserialization.For
import difft.android.messageserialization.PublicKeyInfoStore
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.tencent.wcdb.winq.Expression
import org.difft.app.database.WCDB
import org.difft.app.database.models.GroupMemberContactorModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.PublicKeyInfoModel
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationManagerImplTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val chatHttpClient: ChativeHttpClient = mockk(relaxed = true)
    private val httpService: HttpService = mockk()
    private val publicKeyInfoStore: PublicKeyInfoStore = mockk(relaxed = true)
    private val groupUtil: GroupUtil = mockk(relaxed = true)
    private val wcdb: WCDB = mockk(relaxed = true)

    private lateinit var manager: ConversationManagerImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Mock top-level globalServices for `globalServices.myId` access and
        // `globalServices.userManager.getUserData()?.microToken` (post-issue-#725
        // replacement of the deleted SecureSharedPrefsUtil.getToken()).
        val mockUserManager = mockk<UserManager>(relaxed = true).also {
            val userData = UserData().apply { microToken = "test-token" }
            every { it.getUserData() } returns userData
        }
        val mockGlobal = mockk<GlobalHiltEntryPoint>(relaxed = true).also {
            every { it.myId } returns "selfUid"
            every { it.userManager } returns mockUserManager
        }
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns mockGlobal

        every { chatHttpClient.httpService } returns httpService

        manager = ConversationManagerImpl(
            chatHttpClient,
            publicKeyInfoStore,
            groupUtil,
            wcdb,
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
        Dispatchers.resetMain()
        clearMocks(chatHttpClient, httpService, publicKeyInfoStore, groupUtil, wcdb)
    }

    private fun serverKey(id: String, key: String = "k-$id", reg: Int = 1) = PublicKeyInfo(
        uid = id,
        identityKey = key,
        registrationId = reg,
        resetIdentityKeyTime = 0
    )

    private fun stubServerReturns(keys: List<PublicKeyInfo>?) {
        coEvery { httpService.getPublicKeys(any(), any()) } returns
            BaseResponse<GetPublicKeysResp>(
                ver = 1,
                status = 0,
                reason = null,
                data = keys?.let { GetPublicKeysResp(it) }
            )
    }

    private fun stubGroupMembers(gid: String, memberIds: List<String>) {
        val memberRows = memberIds.map { id ->
            GroupMemberContactorModel().also { it.id = id; it.gid = gid }
        }
        // The top-level extension `val GroupModel.members` resolves to
        // `wcdb.groupMemberContactor.getAllObjects(DBGroupMemberContactorModel.gid.eq(gid))`.
        // So stubbing the WCDB call is sufficient and avoids static mocking of
        // the extension module.
        val groupModel = GroupModel().apply { this.gid = gid }
        every<GroupModel?> {
            wcdb.group.getFirstObject(any<Expression>())
        } returns groupModel
        every<List<GroupMemberContactorModel>> {
            wcdb.groupMemberContactor.getAllObjects(any<Expression>())
        } returns memberRows
        coEvery { groupUtil.getSingleGroupInfo(gid, any()) } returns groupModel
    }

    @Test
    fun hasPublicKeyInfoData_account_resolves_peer_plus_self_and_delegates_to_store() = runTest {
        val captured = slot<Collection<String>>()
        coEvery { publicKeyInfoStore.hasAllUids(capture(captured)) } returns true

        val result = manager.hasPublicKeyInfoData(For.Account("peer"))

        assertTrue(result)
        assertEquals(listOf("peer", "selfUid"), captured.captured.toList())
    }

    @Test
    @Ignore("WCDB Expression class loads native lib; MockK cannot instantiate it for any() matcher. Run as instrumentation test.")
    fun hasPublicKeyInfoData_group_resolves_members_only_no_self() = runTest {
        val gid = "g1"
        val members = listOf("m1", "m2", "m3")
        stubGroupMembers(gid, members)

        val capturedUids = slot<Collection<String>>()
        coEvery { publicKeyInfoStore.hasAllUids(capture(capturedUids)) } returns true

        val result = manager.hasPublicKeyInfoData(For.Group(gid))

        assertTrue(result)
        assertEquals(members, capturedUids.captured.toList(), "Group resolution should be members-only (no self)")
        // Critically: local-only read, NO network call
        coVerify(exactly = 0) { groupUtil.getSingleGroupInfo(any(), any()) }
        coVerify(exactly = 0) { groupUtil.fetchAndSaveSingleGroupInfo(any(), any()) }
        unmockkStatic("org.difft.app.database.WCDBExtensionsKt")
    }

    @Test
    fun updatePublicKeyInfoData_for_account_delegates_to_uids_overload_with_peer_plus_self() = runTest {
        stubServerReturns(listOf(serverKey("peer"), serverKey("selfUid")))

        val reqSlot = slot<GetPublicKeysReq>()
        coEvery { httpService.getPublicKeys(any(), capture(reqSlot)) } answers {
            BaseResponse(
                ver = 1, status = 0, reason = null,
                data = GetPublicKeysResp(listOf(serverKey("peer"), serverKey("selfUid")))
            )
        }

        val result = manager.updatePublicKeyInfoData(For.Account("peer"))

        assertTrue(result)
        assertEquals(listOf("peer", "selfUid"), reqSlot.captured.uids)
        coVerify(exactly = 1) { publicKeyInfoStore.upsert(any()) }
    }

    @Test
    @Ignore("WCDB Expression class loads native lib; requires instrumentation test.")
    fun updatePublicKeyInfoData_for_group_delegates_to_uids_overload_with_members_only() = runTest {
        val gid = "g1"
        val members = listOf("m1", "m2")
        stubGroupMembers(gid, members)

        val reqSlot = slot<GetPublicKeysReq>()
        coEvery { httpService.getPublicKeys(any(), capture(reqSlot)) } answers {
            BaseResponse(
                ver = 1, status = 0, reason = null,
                data = GetPublicKeysResp(members.map { serverKey(it) })
            )
        }

        val result = manager.updatePublicKeyInfoData(For.Group(gid))

        assertTrue(result)
        assertEquals(members, reqSlot.captured.uids)  // NO self
        coVerify(exactly = 1) { publicKeyInfoStore.upsert(any()) }
        unmockkStatic("org.difft.app.database.WCDBExtensionsKt")
    }

    @Test
    fun updatePublicKeyInfoData_uids_server_hit_upserts_on_success() = runTest {
        stubServerReturns(listOf(serverKey("a"), serverKey("b")))

        val result = manager.updatePublicKeyInfoData(listOf("a", "b"))

        assertTrue(result)
        coVerify(exactly = 1) { publicKeyInfoStore.upsert(any()) }
    }

    @Test
    fun updatePublicKeyInfoData_uids_server_null_returns_false_no_upsert() = runTest {
        stubServerReturns(null)

        val result = manager.updatePublicKeyInfoData(listOf("a"))

        assertFalse(result)
        coVerify(exactly = 0) { publicKeyInfoStore.upsert(any()) }
    }

    @Test
    fun updatePublicKeyInfoData_uids_empty_returns_true_no_server_call() = runTest {
        val result = manager.updatePublicKeyInfoData(emptyList())

        assertTrue(result)
        coVerify(exactly = 0) { httpService.getPublicKeys(any(), any()) }
        coVerify(exactly = 0) { publicKeyInfoStore.upsert(any()) }
    }

    @Test
    fun getPublicKeyInfos_for_room_reads_store_via_resolved_uids_preserves_order() = runTest {
        val byUid = mapOf(
            "peer" to PublicKeyInfoModel().apply {
                uid = "peer"; identityKey = "k-peer"; registrationId = 1
            },
            "selfUid" to PublicKeyInfoModel().apply {
                uid = "selfUid"; identityKey = "k-selfUid"; registrationId = 2
            }
        )
        coEvery { publicKeyInfoStore.getForUids(listOf("peer", "selfUid")) } returns byUid

        val result = manager.getPublicKeyInfos(For.Account("peer"))

        assertEquals(2, result.size)
        assertEquals("peer", result[0].uid)          // first in request
        assertEquals("selfUid", result[1].uid)       // second in request
    }

    @Test
    fun getPublicKeyInfos_bypass_server_fetch_ignores_cache() = runTest {
        stubServerReturns(listOf(serverKey("x")))

        val result = manager.getPublicKeyInfos(listOf("x"))

        assertEquals(1, result?.size)
        assertEquals("x", result?.get(0)?.uid)
        coVerify(exactly = 0) { publicKeyInfoStore.getForUids(any()) }
    }

    @Test
    fun getPublicKeyInfos_bypass_null_ids_returns_null() = runTest {
        val result = manager.getPublicKeyInfos(null)

        assertNull(result)
        coVerify(exactly = 0) { httpService.getPublicKeys(any(), any()) }
    }

    @Test
    @Ignore("WCDB Expression class loads native lib; requires instrumentation test.")
    fun hasPublicKeyInfoData_group_local_row_absent_returns_false_no_network() = runTest {
        // Local group row absent → resolveUidsLocalOnly returns [] → cache miss.
        every<GroupModel?> {
            wcdb.group.getFirstObject(any<Expression>())
        } returns null

        val result = manager.hasPublicKeyInfoData(For.Group("g-missing"))

        assertFalse(result)
        coVerify(exactly = 0) { groupUtil.fetchAndSaveSingleGroupInfo(any(), any()) }
        coVerify(exactly = 0) { publicKeyInfoStore.hasAllUids(any()) }  // empty-list guard
    }

    // -----------------------------------------------------------------
    // issue #970 ②: updatePublicKeyInfoDataResult — null/empty signal split.
    // These assert the CLASSIFICATION enum. Downstream permanent/transient mapping (in
    // NewSignalServiceMessageSender) treats ONLY EntityInvalid (group status != 0) as permanent;
    // ServerEmpty (empty keys array) is mapped to transient — an empty array is ambiguous (entity
    // gone vs valid recipient mid key-propagation), see PR #973 code-review. So the result enum
    // still distinguishes ServerEmpty from FetchFailed (for logging), but both retry downstream.
    // -----------------------------------------------------------------

    /** T11/G-invalid (runnable): group status!=0 → EntityInvalid (permanent). No members access. */
    @Test
    fun updateResult_group_status_invalid_returns_entityInvalid() = runTest {
        val gid = "g-invalid"
        val invalidGroup = GroupModel().apply { this.gid = gid; this.status = 1 }
        coEvery { groupUtil.getSingleGroupInfo(gid, any()) } returns invalidGroup

        val result = manager.updatePublicKeyInfoDataResult(For.Group(gid))

        assertEquals(PublicKeyUpdateResult.EntityInvalid, result)
        // status!=0 short-circuits before any getPublicKeys / members read.
        coVerify(exactly = 0) { httpService.getPublicKeys(any(), any()) }
    }

    /** T12/G-unresolved (runnable): group==null (fetch threw / concurrent guard) → Unresolved (transient). */
    @Test
    fun updateResult_group_null_returns_unresolved() = runTest {
        val gid = "g-null"
        coEvery { groupUtil.getSingleGroupInfo(gid, any()) } returns null

        val result = manager.updatePublicKeyInfoDataResult(For.Group(gid))

        assertEquals(PublicKeyUpdateResult.Unresolved, result)
        coVerify(exactly = 0) { httpService.getPublicKeys(any(), any()) }
    }

    /** ARCH-CRIT-1 (runnable): concurrent guard returns null → Unresolved (transient, not permanent). */
    @Test
    fun updateResult_group_concurrent_skip_null_returns_unresolved_transient() = runTest {
        // groupsInProgress concurrent guard makes the 2nd concurrent call return null.
        val gid = "g-concurrent"
        coEvery { groupUtil.getSingleGroupInfo(gid, any()) } returns null

        val result = manager.updatePublicKeyInfoDataResult(For.Group(gid))

        // Must be transient — next round each job resolves status!=0 → permanent.
        assertEquals(PublicKeyUpdateResult.Unresolved, result)
    }

    /** T3 (runnable): For.Account, getPublicKeys throws → FetchFailed (transient). */
    @Test
    fun updateResult_account_fetch_throws_returns_fetchFailed() = runTest {
        coEvery { httpService.getPublicKeys(any(), any()) } throws java.io.IOException("network down")

        val result = manager.updatePublicKeyInfoDataResult(For.Account("peer"))

        assertEquals(PublicKeyUpdateResult.FetchFailed, result)
        coVerify(exactly = 0) { publicKeyInfoStore.upsert(any()) }
    }

    /** T4 (runnable): For.Account, server returns empty array → ServerEmpty (transient downstream). */
    @Test
    fun updateResult_account_server_empty_array_returns_serverEmpty() = runTest {
        stubServerReturns(emptyList())

        val result = manager.updatePublicKeyInfoDataResult(For.Account("peer"))

        assertEquals(PublicKeyUpdateResult.ServerEmpty, result)
        coVerify(exactly = 0) { publicKeyInfoStore.upsert(any()) }
    }

    /** Boundary (runnable): server body null (.data?.keys==null) → FetchFailed (transient, conservative). */
    @Test
    fun updateResult_account_server_body_null_returns_fetchFailed() = runTest {
        stubServerReturns(null)

        val result = manager.updatePublicKeyInfoDataResult(For.Account("peer"))

        assertEquals(PublicKeyUpdateResult.FetchFailed, result)
        coVerify(exactly = 0) { publicKeyInfoStore.upsert(any()) }
    }

    /** T5 (runnable): For.Account, server returns keys → Updated + upsert. */
    @Test
    fun updateResult_account_server_keys_returns_updated_and_upserts() = runTest {
        stubServerReturns(listOf(serverKey("peer"), serverKey("selfUid")))

        val result = manager.updatePublicKeyInfoDataResult(For.Account("peer"))

        assertEquals(PublicKeyUpdateResult.Updated, result)
        coVerify(exactly = 1) { publicKeyInfoStore.upsert(any()) }
    }

    /**
     * G6 (ignored — needs native WCDB Expression for group.members read):
     * group status==0 but members empty → resolved 0 uids → ServerEmpty (transient downstream).
     */
    @Test
    @Ignore("group.members read instantiates native WCDB Expression; run as instrumentation test.")
    fun updateResult_group_valid_but_empty_members_returns_serverEmpty() = runTest {
        val gid = "g-empty"
        val validGroup = GroupModel().apply { this.gid = gid; this.status = 0 }
        coEvery { groupUtil.getSingleGroupInfo(gid, any()) } returns validGroup
        every<List<GroupMemberContactorModel>> {
            wcdb.groupMemberContactor.getAllObjects(any<Expression>())
        } returns emptyList()

        val result = manager.updatePublicKeyInfoDataResult(For.Group(gid))

        assertEquals(PublicKeyUpdateResult.ServerEmpty, result)
    }

    // -----------------------------------------------------------------
    // classifyEmptyKeys — reuses updatePublicKeyInfoDataResult (Round2-A1).
    // -----------------------------------------------------------------

    /** T6 (runnable): group status!=0 → EntityInvalid (permanent for :442 path). */
    @Test
    fun classifyEmptyKeys_group_status_invalid_returns_entityInvalid() = runTest {
        val gid = "g-invalid2"
        val invalidGroup = GroupModel().apply { this.gid = gid; this.status = 1 }
        coEvery { groupUtil.getSingleGroupInfo(gid, any()) } returns invalidGroup

        val result = manager.classifyEmptyKeys(For.Group(gid))

        assertEquals(PublicKeyUpdateResult.EntityInvalid, result)
    }

    /** T7 (runnable): account server keys present → Updated → caller maps to transient. */
    @Test
    fun classifyEmptyKeys_account_server_keys_returns_updated_transient_mapping() = runTest {
        stubServerReturns(listOf(serverKey("peer"), serverKey("selfUid")))

        val result = manager.classifyEmptyKeys(For.Account("peer"))

        // Updated → caller (createNewOutgoingPushMessage) treats as transient (retry self-heal).
        assertEquals(PublicKeyUpdateResult.Updated, result)
    }

    /** HIGH-1 (runnable): account confirming fetch returns empty → ServerEmpty (classification; transient downstream). */
    @Test
    fun classifyEmptyKeys_account_server_empty_returns_serverEmpty_permanent() = runTest {
        stubServerReturns(emptyList())

        val result = manager.classifyEmptyKeys(For.Account("peer"))

        assertEquals(PublicKeyUpdateResult.ServerEmpty, result)
    }

    /**
     * T7 (ignored — needs native WCDB Expression for group.members read, same constraint as G6):
     * For.Group status==0 with non-empty members + server returns keys → Updated → caller maps to
     * transient (retry self-heal). The Updated→transient mapping itself is exercised runnable by
     * [classifyEmptyKeys_account_server_keys_returns_updated_transient_mapping]; this @Ignore variant
     * documents the For.Group resolveUidsWithStatus=Resolved (status==0) path for completeness.
     */
    @Test
    @Ignore("group.members read instantiates native WCDB Expression; run as instrumentation test.")
    fun classifyEmptyKeys_group_valid_with_members_returns_updated_transient() = runTest {
        val gid = "g-valid-members"
        val validGroup = GroupModel().apply { this.gid = gid; this.status = 0 }
        coEvery { groupUtil.getSingleGroupInfo(gid, any()) } returns validGroup
        every<List<GroupMemberContactorModel>> {
            wcdb.groupMemberContactor.getAllObjects(any<Expression>())
        } returns listOf(GroupMemberContactorModel().apply { id = "m1"; this.gid = gid })
        stubServerReturns(listOf(serverKey("m1"), serverKey("selfUid")))

        val result = manager.classifyEmptyKeys(For.Group(gid))

        assertEquals(PublicKeyUpdateResult.Updated, result)
    }
}
