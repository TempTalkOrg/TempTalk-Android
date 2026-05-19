package com.difft.android.chat.group

import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.appScope
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.network.BaseResponse
import com.difft.android.network.group.GetGroupInfoResp
import com.difft.android.network.group.GroupRepo
import com.difft.android.network.group.Member
import difft.android.messageserialization.MessageStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDB
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 trigger tests for [GroupUtil.fetchAndSaveSingleGroupInfo] — verify
 * that a successful fetch dispatches an async [GroupCryptoRepo.verifyAllPendingForGroup]
 * call, an invalid status does NOT, and a verify failure does not break the
 * main fetch.
 *
 * `@Ignore`d at the class level — production code in `fetchAndSaveSingleGroupInfo`
 * constructs WCDB [com.tencent.wcdb.winq.Expression] (`DBGroupModel.gid.eq(..)`,
 * etc.) which triggers `CppObject.<clinit>` → `System.loadLibrary("WCDB")` →
 * native methods unavailable in the JVM. Same constraint as
 * [com.difft.android.chat.common.ConversationManagerImplTest]'s ignored case
 * (line 135) and [org.difft.app.database.models.JobModelRoundTripTest].
 *
 * Tests are written end-to-end so they document the trigger contract; promote
 * to the instrumentation source set when a device-backed harness is available.
 *
 * Mocking strategy (intent doc):
 *   - `mockkStatic("com.difft.android.base.utils.ExtensionsKt")` so [appScope]
 *     can be replaced with a [TestScope]; `runTest { ... advanceUntilIdle() }`
 *     ensures the launched verify body completes before assertions.
 *   - All other dependencies are MockK relaxed mocks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@Ignore("WCDB native lib not loadable in JVM unit tests (Expression class clinit). " +
        "See ConversationManagerImplTest:135 and JobModelRoundTripTest precedent.")
class GroupUtilPhase4TriggerTest {

    private lateinit var groupUtil: GroupUtil
    private lateinit var groupRepo: GroupRepo
    private lateinit var messageStore: MessageStore
    private lateinit var wcdb: WCDB
    private lateinit var userManager: UserManager
    private lateinit var groupCryptoRepo: GroupCryptoRepo
    private lateinit var groupMemberWriter: GroupMemberWriter

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        // Replace top-level appScope with the test scope so launched coroutines
        // execute under runTest's clock.
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { appScope } returns testScope

        groupRepo = mockk(relaxed = true)
        messageStore = mockk(relaxed = true)
        wcdb = mockk(relaxed = true)
        userManager = mockk(relaxed = true)
        groupCryptoRepo = mockk(relaxed = true)
        groupMemberWriter = mockk(relaxed = true)

        groupUtil = GroupUtil(
            groupRepo = groupRepo,
            messageStore = messageStore,
            wcdb = wcdb,
            userManager = userManager,
            groupCryptoRepo = groupCryptoRepo,
            groupMemberWriter = groupMemberWriter,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun successResp(groupID: String): BaseResponse<GetGroupInfoResp> = BaseResponse(
        ver = 1,
        status = 0,
        reason = null,
        data = GetGroupInfoResp(
            anyoneRemove = false,
            avatar = "",
            ext = false,
            invitationRule = 0,
            members = listOf(
                Member(
                    displayName = "u1",
                    extId = 0,
                    notification = 0,
                    rapidRole = 0,
                    remark = null,
                    role = 2,
                    uid = "u1",
                    useGlobal = false,
                    uidSignature = "sig-u1",
                )
            ),
            messageExpiry = 0,
            name = "G",
            publishRule = 0,
            rejoin = false,
            remindCycle = "",
            version = 1,
            linkInviteSwitch = false,
            privateChat = false,
            messageClearAnchor = 0L,
            criticalAlert = false,
        ),
    )

    private fun invalidResp(groupID: String): BaseResponse<GetGroupInfoResp> = BaseResponse(
        ver = 1,
        status = 1,
        reason = "invalid",
        data = null,
    )

    /** P1: status==0 must fire verifyAllPendingForGroup exactly once. */
    @Test
    fun fetchAndSave_status0_triggers_verifyAllPendingForGroup() = runTest(testDispatcher) {
        val gid = "g1"
        coEvery { groupRepo.getGroupInfo(gid) } returns successResp(gid)

        groupUtil.fetchAndSaveSingleGroupInfo(gid, sendUpdateEvent = false)
        advanceUntilIdle()

        coVerify(exactly = 1) { groupCryptoRepo.verifyAllPendingForGroup(gid, groupRepo) }
    }

    /** P2: status!=0 must NOT trigger verify (invalid group cleanup path). */
    @Test
    fun fetchAndSave_status_non0_does_not_trigger_verify() = runTest(testDispatcher) {
        val gid = "g2"
        coEvery { groupRepo.getGroupInfo(gid) } returns invalidResp(gid)

        groupUtil.fetchAndSaveSingleGroupInfo(gid, sendUpdateEvent = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { groupCryptoRepo.verifyAllPendingForGroup(any(), any()) }
    }

    /** P3: A throwing verifyAllPendingForGroup is isolated by runCatching — main fetch returns the group. */
    @Test
    fun verify_failure_does_not_break_main_fetch() = runTest(testDispatcher) {
        val gid = "g3"
        coEvery { groupRepo.getGroupInfo(gid) } returns successResp(gid)
        coEvery { groupCryptoRepo.verifyAllPendingForGroup(any(), any()) } throws RuntimeException("boom")

        val result = groupUtil.fetchAndSaveSingleGroupInfo(gid, sendUpdateEvent = false)
        advanceUntilIdle()

        // Assertion left as documented intent — runs only on instrumentation harness.
        assert(result != null) { "fetchAndSaveSingleGroupInfo must return the group despite verify failure" }
    }
}
