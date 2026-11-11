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
            groupExpiryTracker = GroupExpiryTracker(),
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

    
    @Test
    fun fetchAndSave_status0_triggers_verifyAllPendingForGroup() = runTest(testDispatcher) {
        val gid = "g1"
        coEvery { groupRepo.getGroupInfo(gid) } returns successResp(gid)

        groupUtil.fetchAndSaveSingleGroupInfo(gid, sendUpdateEvent = false)
        advanceUntilIdle()

        coVerify(exactly = 1) { groupCryptoRepo.verifyAllPendingForGroup(gid, groupRepo) }
    }

    
    @Test
    fun fetchAndSave_status_non0_does_not_trigger_verify() = runTest(testDispatcher) {
        val gid = "g2"
        coEvery { groupRepo.getGroupInfo(gid) } returns invalidResp(gid)

        groupUtil.fetchAndSaveSingleGroupInfo(gid, sendUpdateEvent = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { groupCryptoRepo.verifyAllPendingForGroup(any(), any()) }
    }

    
    @Test
    fun verify_failure_does_not_break_main_fetch() = runTest(testDispatcher) {
        val gid = "g3"
        coEvery { groupRepo.getGroupInfo(gid) } returns successResp(gid)
        coEvery { groupCryptoRepo.verifyAllPendingForGroup(any(), any()) } throws RuntimeException("boom")

        val result = groupUtil.fetchAndSaveSingleGroupInfo(gid, sendUpdateEvent = false)
        advanceUntilIdle()

        
        assert(result != null) { "fetchAndSaveSingleGroupInfo must return the group despite verify failure" }
    }
}
