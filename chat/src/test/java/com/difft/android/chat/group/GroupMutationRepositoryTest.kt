package com.difft.android.chat.group

import com.difft.android.base.utils.globalServices
import com.difft.android.chat.crypto.GroupMemberBindingSigner
import com.difft.android.base.storage.user.CredentialChangeSignal
import kotlinx.coroutines.flow.MutableStateFlow
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.chat.crypto.GroupKeyDistributor
import com.difft.android.chat.group.mvi.GroupMutationRepository
import com.difft.android.chat.group.mvi.GroupPageStore
import com.difft.android.chat.jobmanager.QueueAdmission
import com.difft.android.network.BaseResponse
import com.difft.android.network.group.CreateGroupResp
import com.difft.android.network.group.GroupRepo
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import com.difft.android.test.GroupTestApplication_Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject
import javax.inject.Named
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = GroupTestApplication_Application::class, sdk = [33])
class GroupMutationRepositoryTest {
    
    @BindValue @JvmField val logoutBoundary: com.difft.android.base.user.LogoutManager = mockk(relaxed = true)
    @BindValue @JvmField val environmentBoundary: com.difft.android.base.utils.EnvironmentHelper = mockk(relaxed = true)
    @BindValue @JvmField val activityBoundary: com.difft.android.base.activity.ActivityProvider = mockk(relaxed = true)
    @BindValue @JvmField val callToChatBoundary: com.difft.android.call.LCallToChatController = mockk(relaxed = true)
    @BindValue @JvmField val chatToCallBoundary: com.difft.android.chat.call.LChatToCallController = mockk(relaxed = true)
    @BindValue @JvmField val translationBoundary: com.difft.android.chat.translate.backend.TranslateBackend = mockk(relaxed = true)

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatcher = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule("owner")
    @BindValue @JvmField val groups: GroupRepo = mockk()
    @BindValue @JvmField val util: GroupUtil = mockk()
    @BindValue @JvmField val crypto: GroupCryptoRepo = mockk()
    @BindValue @JvmField val distributor: GroupKeyDistributor = mockk()
    @BindValue @JvmField val store: GroupPageStore = mockk()
    @BindValue @JvmField val signer: GroupMemberBindingSigner = mockk()
    @BindValue @JvmField val credentials: CredentialChangeSignal = mockk()
    private val login = MutableStateFlow<String?>("login-a")
    @javax.inject.Inject @Named("application") lateinit var applicationScope: CoroutineScope
    @Inject lateinit var repository: GroupMutationRepository

    @Before fun setUp() {
        every { store.snapshot("group") } returns snapshot()
        coEvery { groups.addMembersWithBindings(any(), any(), any()) } returns BaseResponse(1, 0, null, CreateGroupResp("group", null))
        coEvery { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } returns null
        every { util.fetchMark() } returns 7L
        every { credentials.credentialHash } returns login
        every { credentials.isLoaded } returns true
        every { credentials.firstLoad } returns CompletableDeferred("login-a")
        hilt.inject()
    }

    @After fun tearDown() {
        applicationScope.cancel()
    }

    @Test fun `missing encrypted key rejects before membership request`() = runBlocking {
        every { store.snapshot("group") } returns snapshot(encrypted = true)
        every { crypto.getRGroupBytes("group") } returns null
        assertIs<GroupMutationRepository.Outcome.Rejected>(repository.submit("group", add()).await())
        coVerify(exactly = 0) { groups.addMembersWithBindings(any(), any(), any()) }
    }

    @Test fun `accepted refresh failure still attempts every key admission and retry never repeats membership`() = runBlocking {
        every { store.snapshot("group") } returns snapshot(encrypted = true)
        every { crypto.getRGroupBytes("group") } returns ByteArray(32)
        every { signer.sign(any(), any()) } returns listOf(com.difft.android.network.group.GroupMemberBinding("new", "signature"))
        val jobs = GroupKeyDistributor.PendingMembers("group", "login-a", mapOf("new" to mockk(relaxed = true)))
        every { distributor.prepareMembers("group", listOf("new"), true, "login-a") } returns GroupKeyDistributor.Preparation.Ready(jobs)
        coEvery { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } throws IllegalStateException("refresh")
        coEvery { distributor.enqueueMembersPersisted(jobs) } returns mapOf("new" to QueueAdmission.Unconfirmed("job", "io"))
        val first = assertIs<GroupMutationRepository.Outcome.AppliedFollowupFailed>(repository.submit("group", add()).await())
        assertTrue(first.refreshPending)
        assertEquals(setOf("new"), first.failedRecipients)
        coEvery { distributor.enqueueMembersPersisted(jobs) } returns mapOf("new" to QueueAdmission.Persisted("job"))
        val retry = assertIs<GroupMutationRepository.Outcome.AppliedFollowupFailed>(repository.retry(first.pending).await())
        assertTrue(retry.failedRecipients.isEmpty())
        coVerify(exactly = 1) { groups.addMembersWithBindings("group", listOf("new"), any()) }
        coVerify(exactly = 2) { distributor.enqueueMembersPersisted(jobs) }
    }

    @Test fun `accepted refresh asks for a fetch started after the acceptance mark`() = runBlocking {
        coEvery { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } returns org.difft.app.database.models.GroupModel().apply { gid = "group"; status = 0 }
        assertIs<GroupMutationRepository.Outcome.Applied>(repository.submit("group", add()).await())
        coVerify(exactly = 1) { util.fetchAndSaveSingleGroupInfo("group", true, any(), 7L) }
    }

    @Test fun `closing page cancels its waiter but accepted work completes in application scope`() = runBlocking {
        val accepted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val refreshed = CompletableDeferred<Unit>()
        coEvery { groups.addMembersWithBindings(any(), any(), any()) } coAnswers {
            accepted.complete(Unit)
            releaseResponse.await()
            BaseResponse(1, 0, null, CreateGroupResp("group", null))
        }
        coEvery { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } coAnswers { refreshed.complete(Unit); null }
        val operation = repository.submit("group", add())
        val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val waiter = pageScope.async { operation.await() }
        withTimeout(5_000) { accepted.await() }
        pageScope.cancel()
        releaseResponse.complete(Unit)
        withTimeout(5_000) { refreshed.await() }
        assertIs<GroupMutationRepository.Outcome.AppliedFollowupFailed>(operation.await())
        assertTrue(waiter.isCancelled)
        coVerify(exactly = 1) { groups.addMembersWithBindings(any(), any(), any()) }
    }

    @Test fun `same account session change after acceptance cannot refresh the new session`() = runBlocking {
        coEvery { groups.addMembersWithBindings(any(), any(), any()) } coAnswers {
            login.value = "login-b"
            BaseResponse(1, 0, null, CreateGroupResp("group", null))
        }
        val result = assertIs<GroupMutationRepository.Outcome.AppliedFollowupFailed>(repository.submit("group", add()).await())
        assertEquals("account_changed", result.stage)
        coVerify(exactly = 0) { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) }
    }

    @Test fun `reopened page can only retry the accepted pending operation`() = runBlocking {
        val first = assertIs<GroupMutationRepository.Outcome.AppliedFollowupFailed>(repository.submit("group", add()).await())
        assertEquals(first.pending, repository.pendingFor("group", "owner"))
        assertIs<GroupMutationRepository.Outcome.Rejected>(repository.submit("group", add()).await())
        coVerify(exactly = 1) { groups.addMembersWithBindings(any(), any(), any()) }
    }

    @Test fun `account change after acceptance cannot refresh the newly signed in database`() = runBlocking {
        coEvery { groups.addMembersWithBindings(any(), any(), any()) } coAnswers {
            every { globalServices.myId } returns "other"
            BaseResponse(1, 0, null, CreateGroupResp("group", null))
        }
        val result = assertIs<GroupMutationRepository.Outcome.AppliedFollowupFailed>(repository.submit("group", add()).await())
        assertEquals("account_changed", result.stage)
        coVerify(exactly = 0) { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) }
    }

    private fun add() = GroupMutationRepository.Change.Members(listOf("new"), add = true)
    private fun snapshot(encrypted: Boolean = false) = GroupPageStore.Snapshot("group", 0, 2, 2,
        false, false, encrypted, listOf(GroupMemberModel("Owner", "owner", null, null, "O", GROUP_ROLE_OWNER)))
}
