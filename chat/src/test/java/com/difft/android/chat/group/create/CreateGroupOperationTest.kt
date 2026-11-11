package com.difft.android.chat.group.create

import com.difft.android.base.storage.user.CredentialChangeSignal
import com.difft.android.chat.crypto.GroupCryptoRepo
import com.difft.android.chat.crypto.GroupKeyDistributor
import com.difft.android.chat.group.GroupAvatarUploader
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.group.GroupWriteSession
import com.difft.android.chat.group.create.CreateGroupOperation.Outcome
import com.difft.android.chat.jobmanager.QueueAdmission
import com.difft.android.network.BaseResponse
import com.difft.android.network.group.CreateGroupReq
import com.difft.android.network.group.GroupMemberBinding
import com.difft.android.network.group.CreateGroupResp
import com.difft.android.network.group.GroupRepo
import com.difft.android.network.group.Stranger
import com.difft.android.test.GroupTestApplication_Application
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.difft.app.database.models.GroupModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue






@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = GroupTestApplication_Application::class, sdk = [33])
class CreateGroupOperationTest {
    @BindValue @JvmField val logoutBoundary: com.difft.android.base.user.LogoutManager = mockk(relaxed = true)
    @BindValue @JvmField val environmentBoundary: com.difft.android.base.utils.EnvironmentHelper = mockk(relaxed = true)
    @BindValue @JvmField val activityBoundary: com.difft.android.base.activity.ActivityProvider = mockk(relaxed = true)
    @BindValue @JvmField val callToChatBoundary: com.difft.android.call.LCallToChatController = mockk(relaxed = true)
    @BindValue @JvmField val chatToCallBoundary: com.difft.android.chat.call.LChatToCallController = mockk(relaxed = true)
    @BindValue @JvmField val translationBoundary: com.difft.android.chat.translate.backend.TranslateBackend = mockk(relaxed = true)

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatcher = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule("owner")
    @get:Rule(order = 3) val files = TemporaryFolder()
    @BindValue @JvmField val groups: GroupRepo = mockk()
    @BindValue @JvmField val util: GroupUtil = mockk()
    @BindValue @JvmField val crypto: GroupCryptoRepo = mockk()
    @BindValue @JvmField val distributor: GroupKeyDistributor = mockk()
    @BindValue @JvmField val uploader: GroupAvatarUploader = mockk()
    @BindValue @JvmField val requests: CreateGroupRequestBuilder = mockk()
    @BindValue @JvmField val credentials: CredentialChangeSignal = mockk()
    private val login = MutableStateFlow<String?>("login-a")
    @Inject @Named("application") lateinit var applicationScope: CoroutineScope
    @Inject lateinit var operation: CreateGroupOperation

    private val session = GroupWriteSession("owner", "login-a")

    @Before fun setUp() {
        
        every { requests.build(any(), any()) } answers {
            val draft = firstArg<CreateGroupOperation.Draft>()
            if (draft.encrypted) CreateGroupRequestBuilder.Built(
                CreateGroupReq(name = null, numbers = draft.memberIds, avatar = null, groupCryptoMode = 1, encryptedName = "enc:" + draft.name,
                    encryptedAvatar = secondArg<String?>()?.let { "enc:$it" }, groupMemberVerifyPublicKey = "pk",
                    memberBindings = (draft.memberIds + "owner").distinct().map { GroupMemberBinding(it, "sig:$it") }),
                ByteArray(32) { 1 },
            ) else CreateGroupRequestBuilder.Built(CreateGroupReq(name = draft.name, numbers = draft.memberIds, avatar = secondArg()), null)
        }
        every { credentials.credentialHash } returns login
        coEvery { groups.createGroup(any()) } returns BaseResponse(1, 0, null, CreateGroupResp("g1", null))
        every { crypto.saveRGroupIfNeeded(any(), any()) } returns true
        every { crypto.getRGroupBytes(any()) } returns null
        coEvery { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } returns GroupModel().apply { gid = "g1"; status = 0 }
        coEvery { distributor.distributeToGroupPersisted(any(), any()) } returns QueueAdmission.Persisted("job")
        hilt.inject()
    }

    @After fun tearDown() {
        applicationScope.cancel()
    }

    private fun draft(encrypted: Boolean = true, avatar: String? = null) =
        CreateGroupOperation.Draft("Team", listOf("a", "b"), avatar, encrypted)

    @Test fun `encrypted create signs self and members, then saves the key, refreshes with the session and persists the broadcast`() = runBlocking {
        val request = slot<CreateGroupReq>()
        coEvery { groups.createGroup(capture(request)) } returns BaseResponse(1, 0, null, CreateGroupResp("g1", null))
        assertEquals(Outcome.Created("g1"), operation.submit(draft(), session).await())
        assertEquals(1, request.captured.groupCryptoMode)
        assertEquals(setOf("a", "b", "owner"), request.captured.memberBindings!!.map { it.uid }.toSet())
        coVerify(exactly = 1) { crypto.saveRGroupIfNeeded("g1", any()) }
        coVerify(exactly = 1) { util.fetchAndSaveSingleGroupInfo("g1", true, session, null) }
        coVerify(exactly = 1) { distributor.distributeToGroupPersisted("g1", "login-a") }
    }

    @Test fun `plain create sends the name and no bindings, and runs no key follow-up`() = runBlocking {
        val request = slot<CreateGroupReq>()
        coEvery { groups.createGroup(capture(request)) } returns BaseResponse(1, 0, null, CreateGroupResp("g1", null))
        assertEquals(Outcome.Created("g1"), operation.submit(draft(encrypted = false), session).await())
        assertEquals("Team", request.captured.name)
        assertEquals(null, request.captured.memberBindings)
        coVerify(exactly = 0) { crypto.saveRGroupIfNeeded(any(), any()) }
        coVerify(exactly = 0) { distributor.distributeToGroupPersisted(any(), any()) }
    }

    @Test fun `a failed key save is a follow-up failure and the retry resumes without a second create`() = runBlocking {
        every { crypto.saveRGroupIfNeeded(any(), any()) } returns false
        val first = assertIs<Outcome.CreatedFollowupFailed>(operation.submit(draft(), session).await())
        assertEquals("key_save", first.stage)
        every { crypto.saveRGroupIfNeeded(any(), any()) } returns true
        assertEquals(Outcome.Created("g1"), operation.retryFollowups("g1").await())
        coVerify(exactly = 1) { groups.createGroup(any()) }
        coVerify(exactly = 2) { crypto.saveRGroupIfNeeded("g1", any()) }
    }

    @Test fun `a retry after a failed fetch skips the key already stored and finishes the later stages`() = runBlocking {
        coEvery { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } returns null
        assertEquals("fetch", assertIs<Outcome.CreatedFollowupFailed>(operation.submit(draft(), session).await()).stage)
        
        every { crypto.saveRGroupIfNeeded(any(), any()) } returns false
        every { crypto.getRGroupBytes("g1") } returns ByteArray(32) { 1 }
        coEvery { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } returns GroupModel().apply { gid = "g1"; status = 0 }
        assertEquals(Outcome.Created("g1"), operation.retryFollowups("g1").await())
        coVerify(exactly = 1) { crypto.saveRGroupIfNeeded("g1", any()) }
        coVerify(exactly = 1) { distributor.distributeToGroupPersisted("g1", "login-a") }
    }

    @Test fun `a refresh that keeps failing still queues the key broadcast, once`() = runBlocking {
        coEvery { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } returns null
        assertEquals("fetch", assertIs<Outcome.CreatedFollowupFailed>(operation.submit(draft(), session).await()).stage)
        coVerify(exactly = 1) { distributor.distributeToGroupPersisted("g1", "login-a") }
        assertEquals("fetch", assertIs<Outcome.CreatedFollowupFailed>(operation.retryFollowups("g1").await()).stage)
        coVerify(exactly = 1) { distributor.distributeToGroupPersisted("g1", "login-a") }
        coVerify(exactly = 2) { util.fetchAndSaveSingleGroupInfo("g1", true, session, null) }
    }

    @Test fun `an unpersisted broadcast and a null refresh are follow-up failures, not successes`() = runBlocking {
        coEvery { distributor.distributeToGroupPersisted(any(), any()) } returns QueueAdmission.Unconfirmed("job", "io")
        assertEquals("key_admission", assertIs<Outcome.CreatedFollowupFailed>(operation.submit(draft(), session).await()).stage)
        coEvery { distributor.distributeToGroupPersisted(any(), any()) } returns QueueAdmission.Persisted("job")
        coEvery { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) } returns null
        assertEquals("fetch", assertIs<Outcome.CreatedFollowupFailed>(operation.submit(draft(), session).await()).stage)
    }

    @Test fun `an account change after acceptance writes nothing locally`() = runBlocking {
        coEvery { groups.createGroup(any()) } coAnswers {
            login.value = "login-b"
            BaseResponse(1, 0, null, CreateGroupResp("g1", null))
        }
        val outcome = assertIs<Outcome.CreatedFollowupFailed>(operation.submit(draft(), session).await())
        assertEquals("account_changed", outcome.stage)
        coVerify(exactly = 0) { crypto.saveRGroupIfNeeded(any(), any()) }
        coVerify(exactly = 0) { util.fetchAndSaveSingleGroupInfo(any(), any(), any(), any()) }
    }

    @Test fun `a send that gets no answer is rejected as unknown, a server rejection carries status and strangers`() = runBlocking {
        coEvery { groups.createGroup(any()) } throws IOException("reset")
        val unknown = assertIs<Outcome.Rejected>(operation.submit(draft(), session).await())
        assertTrue(unknown.unknown)
        coEvery { groups.createGroup(any()) } returns BaseResponse(1, 10125, "not friends", CreateGroupResp("", listOf(Stranger("Bob", "b"))))
        val rejected = assertIs<Outcome.Rejected>(operation.submit(draft(), session).await())
        assertEquals(10125, rejected.status)
        assertEquals("Bob", rejected.strangers)
        assertFalse(rejected.unknown)
    }

    @Test fun `the avatar draft is uploaded, deleted after the group exists, and kept after a rejection`() = runBlocking {
        val file = files.newFile("avatar.png").apply { writeBytes(ByteArray(4)) }
        coEvery { uploader.uploadAndBuildJson(file.absolutePath) } returns "{\"id\":\"att\"}"
        coEvery { groups.createGroup(any()) } returns BaseResponse(1, 500, "boom", null)
        assertIs<Outcome.Rejected>(operation.submit(draft(encrypted = false, avatar = file.absolutePath), session).await())
        assertTrue(file.exists(), "a rejected create hands the draft back")
        coEvery { groups.createGroup(any()) } returns BaseResponse(1, 0, null, CreateGroupResp("g1", null))
        assertEquals(Outcome.Created("g1"), operation.submit(draft(encrypted = false, avatar = file.absolutePath), session).await())
        assertFalse(file.exists(), "the operation deletes the draft it uploaded")
        coVerify(exactly = 2) { uploader.uploadAndBuildJson(file.absolutePath) }
    }
}
