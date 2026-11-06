package com.difft.android.login.mvi

import android.content.Context
import androidx.lifecycle.Observer
import com.difft.android.chat.common.AvatarPickTempCleaner
import com.difft.android.chat.common.upload.ContactAvatarUploader
import com.difft.android.chat.common.upload.UploadedAvatarMeta
import com.difft.android.login.repo.ProfileSession
import com.difft.android.login.repo.ProfileSessionExpiredException
import com.difft.android.base.user.UserData
import com.difft.android.test.fakes.FakeUserManager
import com.difft.android.login.repo.ProfileSaveRepository
import com.difft.android.login.viewmodel.ContactProfileSettingViewModel
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.requests.AvatarRequestBody
import com.difft.android.network.requests.ProfileRequestBody
import com.difft.android.network.viewmodel.Resource
import com.difft.android.network.viewmodel.Status
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import com.google.gson.Gson
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.assertIs
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The same production saver services registration/signup Resource and hosted MVI callers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileSaveRepositoryTest {
    @get:Rule(order = 0) val dispatcher = TestDispatcherRule()
    @get:Rule(order = 1) val globals = GlobalStaticMockRule()
    private val service = mockk<HttpService>()
    private val client = mockk<ChativeHttpClient> { every { httpService } returns service }
    private val uploader = mockk<ContactAvatarUploader>()
    private val context = mockk<Context>(relaxed = true)
    private val users = FakeUserManager(UserData(account = "test-user-001", baseAuth = "original-session"))

    @After fun cleanup() { unmockkObject(AvatarPickTempCleaner) }

    @Test fun `name only and avatar modes preserve request shape and clean only after acceptance`() = runTest {
        mockkObject(AvatarPickTempCleaner)
        every { AvatarPickTempCleaner.deleteUploadedTemp(any(), any()) } just Runs
        val requests = mutableListOf<ProfileRequestBody>()
        coEvery { service.fetchSetProfile(capture(requests)) } returns BaseResponse(1, 0, null, null)
        coEvery { uploader.uploadAvatar("private-output") } returns UploadedAvatarMeta("attachment", "key")
        val repository = ProfileSaveRepository(client, uploader, Gson(), context, backgroundScope, users)
        repository.save(null, "Name", null)
        repository.save("private-output", "Avatar Name", null)
        assertNull(requests[0].avatar)
        assertEquals("Name", requests[0].name)
        val avatar = Gson().fromJson(requests[1].avatar, AvatarRequestBody::class.java)
        assertEquals(ContactAvatarUploader.AVATAR_ENC_ALGO, avatar.encAlgo)
        assertEquals("attachment", avatar.attachmentId)
        assertEquals("key", avatar.encKey)
        coVerify(exactly = 1) { uploader.uploadAvatar("private-output") }
        verify(exactly = 1) { AvatarPickTempCleaner.deleteUploadedTemp(context, "private-output") }
    }

    @Test fun `registration and signup compatibility delegate to one saver and preserve Resource success`() = runTest {
        val repository = mockk<ProfileSaveRepository>()
        val session = ProfileSession("test-user-001", "original-session")
        every { repository.captureSession() } returns session
        coEvery { repository.save(null, any(), null, session) } returns "accepted"
        val model = ContactProfileSettingViewModel(repository)
        val results = mutableListOf<Status>()
        val observer = Observer<Resource<Any>> { results += it.status }
        model.setProfileResultData.observeForever(observer)
        try {
            model.setProfile(null, "Register", null)
            model.setProfile(null, "Signup", null)
            assertEquals(listOf(Status.LOADING, Status.SUCCESS, Status.LOADING, Status.SUCCESS), results)
            coVerify(exactly = 1) { repository.save(null, "Register", null, session) }
            coVerify(exactly = 1) { repository.save(null, "Signup", null, session) }
        } finally { model.setProfileResultData.removeObserver(observer) }
    }
    @Test fun `account changes during avatar upload prevent following authenticated profile request`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val uploaded = CompletableDeferred<UploadedAvatarMeta>()
        coEvery { uploader.uploadAvatar(any()) } coAnswers { entered.complete(Unit); uploaded.await() }
        val repository = ProfileSaveRepository(client, uploader, Gson(), context, backgroundScope, users)
        val result = async { runCatching { repository.save("private-output", "old-draft", null) } }
        entered.await()
        users.setUserData(UserData(account = "replacement", baseAuth = "new-session"))
        every { globals.mockGlobalServices.myId } returns "replacement"
        uploaded.complete(UploadedAvatarMeta("attachment", "key"))
        assertIs<ProfileSessionExpiredException>(result.await().exceptionOrNull())
        coVerify(exactly = 0) { service.fetchSetProfile(any()) }
    }

    @Test fun `same account reauthentication during upload invalidates the original authorization`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val uploaded = CompletableDeferred<UploadedAvatarMeta>()
        coEvery { uploader.uploadAvatar(any()) } coAnswers { entered.complete(Unit); uploaded.await() }
        val repository = ProfileSaveRepository(client, uploader, Gson(), context, backgroundScope, users)
        val result = async { runCatching { repository.save("private-output", "old-draft", null) } }
        entered.await()
        users.setUserData(UserData(account = "test-user-001", baseAuth = "new-session"))
        uploaded.complete(UploadedAvatarMeta("attachment", "key"))
        assertIs<ProfileSessionExpiredException>(result.await().exceptionOrNull())
        coVerify(exactly = 0) { service.fetchSetProfile(any()) }
    }

    @Test fun `late accepted response never mutates contact from a replaced session`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<BaseResponse<Any>>()
        coEvery { service.fetchSetProfile(any()) } coAnswers { entered.complete(Unit); response.await() }
        val contact = org.difft.app.database.models.ContactorModel().apply { id = "test-user-001"; name = "original" }
        val repository = ProfileSaveRepository(client, uploader, Gson(), context, backgroundScope, users)
        val result = async { runCatching { repository.save(null, "old-draft", contact) } }
        entered.await()
        users.setUserData(UserData(account = "test-user-001", baseAuth = "new-session"))
        response.complete(BaseResponse(1, 0, null, null))
        assertIs<ProfileSessionExpiredException>(result.await().exceptionOrNull())
        assertEquals("original", contact.name)
    }

}
