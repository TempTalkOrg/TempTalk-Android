package com.difft.android.login.mvi

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.difft.android.login.repo.ProfileSaveRepository
import com.difft.android.test.TestDispatcherRule
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.difft.app.database.models.ContactorModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileEditViewModelTest {
    @get:Rule val dispatchers = TestDispatcherRule()

    @Test fun `late initial read cannot overwrite text or avatar and restored draft remains authoritative`() = runTest {
        val repository = mockk<ProfileSaveRepository>()
        val session = com.difft.android.login.repo.ProfileSession("test-user-001", "original-session")
        every { repository.captureSession() } returns session
        val loaded = CompletableDeferred<ContactorModel?>()
        coEvery { repository.load(any()) } coAnswers { loaded.await() }
        val saved = SavedStateHandle()
        val model = ProfileEditViewModel(repository, saved)
        model.dispatch(ProfileEditContract.Intent.ChangeName("typed"))
        model.dispatch(ProfileEditContract.Intent.SelectAvatar("private-avatar"))
        loaded.complete(ContactorModel().apply { id = "self"; name = "old" })
        assertEquals("typed", model.state.value.nameDraft)
        assertEquals("private-avatar", model.state.value.avatarPath)
        val restored = ProfileEditViewModel(repository, SavedStateHandle(mapOf(
            "loaded" to true, "name" to "typed", "nameDirty" to true,
            "avatar" to "private-avatar", "avatarDirty" to true,
        )))
        assertEquals("typed", restored.state.value.nameDraft)
        assertEquals("private-avatar", restored.state.value.avatarPath)
        model.viewModelScope.cancel()
        restored.viewModelScope.cancel()
    }

    @Test fun `save singleflight retains replayable completion without repeating API`() = runTest {
        val repository = mockk<ProfileSaveRepository>()
        val session = com.difft.android.login.repo.ProfileSession("test-user-001", "original-session")
        every { repository.captureSession() } returns session
        coEvery { repository.load(any()) } returns null
        val gate = CompletableDeferred<Any>()
        coEvery { repository.save(any(), any(), any(), any()) } coAnswers { gate.await() }
        val model = ProfileEditViewModel(repository, SavedStateHandle())
        model.dispatch(ProfileEditContract.Intent.ChangeName("typed"))
        model.dispatch(ProfileEditContract.Intent.Save)
        model.dispatch(ProfileEditContract.Intent.Save)
        assertTrue(model.state.value.busy)
        gate.complete("accepted")
        assertTrue(model.state.value.completed)
        model.dispatch(ProfileEditContract.Intent.Save)
        coVerify(exactly = 1) { repository.save(null, "typed", null, session) }
        model.viewModelScope.cancel()
    }
    @Test fun `save after suspended fallback load keeps original page session lease`() = runTest {
        val repository = mockk<ProfileSaveRepository>()
        val original = com.difft.android.login.repo.ProfileSession("original", "old-session")
        val replacement = com.difft.android.login.repo.ProfileSession("replacement", "new-session")
        every { repository.captureSession() } returns original
        val loaded = CompletableDeferred<ContactorModel?>()
        coEvery { repository.load(original) } coAnswers { loaded.await() }
        coEvery { repository.save(any(), any(), any(), original) } returns "accepted"
        val model = ProfileEditViewModel(repository, SavedStateHandle())
        model.dispatch(ProfileEditContract.Intent.ChangeName("old-account-draft"))
        model.dispatch(ProfileEditContract.Intent.Save)
        every { repository.captureSession() } returns replacement
        loaded.complete(null)
        coVerify(exactly = 1) { repository.save(null, "old-account-draft", null, original) }
        verify(exactly = 1) { repository.captureSession() }
        model.viewModelScope.cancel()
    }

    @Test fun `restored draft from another account is cleared without saving authorization`() = runTest {
        val repository = mockk<ProfileSaveRepository>()
        val current = com.difft.android.login.repo.ProfileSession("replacement", "new-authorization")
        every { repository.captureSession() } returns current
        coEvery { repository.load(current) } returns null
        val saved = SavedStateHandle(mapOf("profileAccount" to "old", "name" to "old-draft", "avatar" to "old-avatar", "nameDirty" to true))
        val model = ProfileEditViewModel(repository, saved)
        assertEquals("", model.state.value.nameDraft)
        assertEquals(null, model.state.value.avatarPath)
        assertEquals(false, saved.keys().any { saved.get<Any>(it) == "new-authorization" })
        model.viewModelScope.cancel()
    }

}
