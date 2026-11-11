package com.difft.android.setting.mvi

import androidx.lifecycle.SavedStateHandle
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.difft.android.setting.repo.ScreenLockSettingsRepository
import com.difft.android.test.fakes.FakeUserManager
import com.difft.android.test.rules.GlobalStaticMockRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenLockSettingsViewModelTest {
    @get:Rule val globals = GlobalStaticMockRule()
    private fun user() = FakeUserManager(UserData(account = "test-user-001", email = "masked", passcode = "existing"))

    
    private class RecordingUserManager(private var data: UserData?) : UserManager {
        val blockingCommits = mutableListOf<Boolean>()
        override fun setUserData(userData: UserData, commit: Boolean) { blockingCommits += commit; data = userData }
        override fun getUserData(): UserData? = data
    }

    @Test
    fun `verified disable never requests a blocking commit and rereads stored truth`() = runTest {
        val users = RecordingUserManager(UserData(account = "test-user-001", email = "masked", passcode = "existing"))
        val model = ScreenLockSettingsViewModel(ScreenLockSettingsRepository(users), SavedStateHandle())
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Passcode, false))
        val request = requireNotNull(model.takeVisibleLaunch())
        assertEquals(ScreenLockContract.Stage.AwaitingVerification, model.state.value.pending?.stage)
        model.dispatch(ScreenLockContract.Intent.Result(request.requestId, ScreenLockContract.Stage.AwaitingVerification, true))
        assertEquals(ScreenLockContract.Effect.Disabled(ScreenLockContract.Kind.Passcode), model.effect.first())
        assertFalse(model.state.value.passcode)
        assertNull(users.getUserData()?.passcode)
        assertEquals(listOf(false), users.blockingCommits)
    }

    @Test
    fun `nonce stage and duplicate results never authorize another pending chain`() {
        val users = user()
        val model = ScreenLockSettingsViewModel(ScreenLockSettingsRepository(users), SavedStateHandle())
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Pattern, true))
        val request = requireNotNull(model.takeVisibleLaunch())
        assertEquals(ScreenLockContract.Stage.ReadyForVerification, request.stage)
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Passcode, false))
        model.dispatch(ScreenLockContract.Intent.Result("stale", ScreenLockContract.Stage.AwaitingVerification, true))
        assertEquals(request.requestId, model.state.value.pending?.requestId)
        model.dispatch(ScreenLockContract.Intent.Result(request.requestId, ScreenLockContract.Stage.AwaitingBinding, true))
        assertEquals(ScreenLockContract.Stage.AwaitingVerification, model.state.value.pending?.stage)
        model.dispatch(ScreenLockContract.Intent.Result(request.requestId, ScreenLockContract.Stage.AwaitingVerification, true))
        assertEquals(ScreenLockContract.Stage.ReadyForSetup, model.state.value.pending?.stage)
        model.dispatch(ScreenLockContract.Intent.Result(request.requestId, ScreenLockContract.Stage.AwaitingVerification, true))
        assertEquals(ScreenLockContract.Stage.ReadyForSetup, model.state.value.pending?.stage)
    }

    @Test
    fun `verified enable waits for visibility and process restoration reverifies`() {
        val saved = SavedStateHandle()
        val repository = ScreenLockSettingsRepository(user())
        val model = ScreenLockSettingsViewModel(repository, saved)
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Pattern, true))
        val request = requireNotNull(model.takeVisibleLaunch())
        model.dispatch(ScreenLockContract.Intent.Result(request.requestId, ScreenLockContract.Stage.AwaitingVerification, true))
        assertEquals(ScreenLockContract.Stage.ReadyForSetup, model.state.value.pending?.stage)
        val restored = ScreenLockSettingsViewModel(repository, SavedStateHandle(mapOf("pending" to model.state.value.pending)))
        assertEquals(ScreenLockContract.Stage.ReadyForVerification, restored.state.value.pending?.stage)
        val launch = restored.takeVisibleLaunch()
        assertNotNull(launch)
        assertNull(restored.takeVisibleLaunch())
    }

    @Test
    fun `setup cancelled result rereads persisted truth without relaunching`() {
        val users = FakeUserManager(UserData(account = "test-user-001", email = "masked"))
        val model = ScreenLockSettingsViewModel(ScreenLockSettingsRepository(users), SavedStateHandle())
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Pattern, true))
        val request = requireNotNull(model.takeVisibleLaunch())
        users.update { pattern = "persisted" }
        model.dispatch(ScreenLockContract.Intent.Result(request.requestId, ScreenLockContract.Stage.AwaitingSetup, false))
        assertTrue(model.state.value.pattern)
        assertNull(model.state.value.pending)
        assertNull(model.takeVisibleLaunch())
    }

    @Test
    fun `verification can disable while hidden but old account result cannot write`() {
        val users = user()
        val model = ScreenLockSettingsViewModel(ScreenLockSettingsRepository(users), SavedStateHandle())
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Passcode, false))
        val request = requireNotNull(model.takeVisibleLaunch())
        model.dispatch(ScreenLockContract.Intent.Result(request.requestId, ScreenLockContract.Stage.AwaitingVerification, true))
        assertNull(users.getUserData()?.passcode)
        users.update { passcode = "other" }
        model.dispatch(ScreenLockContract.Intent.Refresh)
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Passcode, false))
        val stale = requireNotNull(model.takeVisibleLaunch())
        io.mockk.every { globals.mockGlobalServices.myId } returns "different-account"
        model.dispatch(ScreenLockContract.Intent.Result(stale.requestId, ScreenLockContract.Stage.AwaitingVerification, true))
        assertEquals("other", users.getUserData()?.passcode)
        assertNull(model.state.value.pending)
    }

    @Test
    fun `binding cancellation and restored awaiting stage never relaunch`() {
        val users = FakeUserManager(UserData(account = "test-user-001"))
        val repository = ScreenLockSettingsRepository(users)
        val model = ScreenLockSettingsViewModel(repository, SavedStateHandle())
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Pattern, true))
        assertEquals(ScreenLockContract.Stage.ChoosingBinding, model.state.value.pending?.stage)
        model.dispatch(ScreenLockContract.Intent.DismissBinding)
        assertNull(model.state.value.pending)
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Pattern, true))
        model.dispatch(ScreenLockContract.Intent.ChooseBinding("email"))
        val request = requireNotNull(model.takeVisibleLaunch())
        val restored = ScreenLockSettingsViewModel(repository, SavedStateHandle(mapOf("pending" to model.state.value.pending)))
        assertNull(restored.takeVisibleLaunch())
        restored.dispatch(ScreenLockContract.Intent.Result(request.requestId, ScreenLockContract.Stage.AwaitingBinding, false))
        assertNull(restored.state.value.pending)
        assertFalse(restored.state.value.pattern)
    }
}
