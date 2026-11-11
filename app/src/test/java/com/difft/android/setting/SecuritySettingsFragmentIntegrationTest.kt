package com.difft.android.setting

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.pane.DetailNavigation
import com.difft.android.base.utils.pane.DetailStackHost
import com.difft.android.network.BaseResponse
import com.difft.android.setting.data.GetProfileResponse
import com.difft.android.setting.mvi.DeleteAccountContract
import com.difft.android.setting.mvi.DeleteAccountViewModel
import com.difft.android.setting.mvi.PasscodeTimeoutContract
import com.difft.android.setting.mvi.PasscodeTimeoutViewModel
import com.difft.android.setting.mvi.ScreenLockContract
import com.difft.android.setting.mvi.ScreenLockSettingsViewModel
import com.difft.android.setting.repo.SettingRepo
import com.difft.android.setting.viewmodel.SetCustomIdContract
import com.difft.android.setting.viewmodel.SetCustomIdViewModel
import com.difft.android.test.FoldableTestApplication_Application
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config


@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = FoldableTestApplication_Application::class, sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class SecuritySettingsFragmentIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule()
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @BindValue @JvmField val settings: SettingRepo = mockk(relaxed = true)
    @BindValue @JvmField val profileRepository: com.difft.android.login.repo.ProfileSaveRepository = mockk(relaxed = true)
    @BindValue @JvmField val groups: com.difft.android.network.group.GroupRepo = mockk(relaxed = true)
    @BindValue @JvmField val strictUsers: StorageBoundUserManager = mockk(relaxed = true)
    @BindValue @JvmField val accounts: com.difft.android.setting.repo.AccountMutationRepository = mockk(relaxed = true)
    @BindValue @JvmField val identity: com.difft.android.setting.repo.IdentityRotationRepository = mockk(relaxed = true)
    @BindValue @JvmField val logoutBoundary: LogoutManagerImpl = mockk(relaxed = true)
    @Inject lateinit var users: UserManager
    private lateinit var controller: ActivityController<SecuritySettingsTestActivity>

    @Before fun setup() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        hilt.inject()
        every { profileRepository.captureSession() } returns mockk(relaxed = true)
        coEvery { profileRepository.load(any()) } returns null
        coEvery { profileRepository.save(any(), any(), any(), any()) } returns "accepted"
        users.update {
            account = "test-user-001"; email = "masked"; pattern = null; passcode = "existing"
            baseAuth = "original-authorization"; customUid = "stored_name"; passcodeTimeout = 60
        }
        controller = Robolectric.buildActivity(SecuritySettingsTestActivity::class.java).setup()
    }
    @After fun teardown() { controller.pause().stop().destroy() }

    private fun show(page: Fragment) {
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, page, "page").commitNow()
        assertNotNull(page.view)
    }
    private fun restored(): Fragment = requireNotNull(controller.get().supportFragmentManager.findFragmentByTag("page"))

    @Test fun `account and privacy roots inflate with their actual bindings and page background identity`() {
        val account = AccountFragment()
        show(account)
        assertEquals(true, com.difft.android.dualpane.IndexDetailPages.usesSettingsBackground(account))
        val privacy = PrivacySettingFragment()
        show(privacy)
        assertEquals(true, com.difft.android.dualpane.IndexDetailPages.usesSettingsBackground(privacy))
    }

    @Test fun `account page rereads the stored search flag when an outliving search write ends`() {
        users.update { searchByCustomUid = 0 }
        val page = AccountFragment()
        show(page)
        val switch = page.requireView().findViewById<com.difft.android.base.widget.DifftSwitchView>(com.difft.android.R.id.switch_can_id_search)
        assertEquals(false, switch.isChecked)
        
        kotlinx.coroutines.runBlocking {
            com.difft.android.base.utils.pane.PageOperations.track("account-search:test-user-001") { users.update { searchByCustomUid = 1 } }
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, ViewModelProvider(page)[com.difft.android.setting.mvi.AccountSettingsViewModel::class.java].state.value.user?.searchByCustomUid)
        assertEquals(true, switch.isChecked)
    }

    @Test fun `tapping the account id text itself opens the custom id editor like its row`() {
        val page = AccountFragment()
        show(page)
        
        page.requireView().findViewById<android.view.View>(com.difft.android.R.id.tv_id).performClick()
        val started = org.robolectric.Shadows.shadowOf(controller.get()).nextStartedActivity
        assertEquals(SetCustomIdActivity::class.java.name, started?.component?.className)
    }

    @Test fun `real profile text survives recreation and late initial load`() {
        val loaded = kotlinx.coroutines.CompletableDeferred<org.difft.app.database.models.ContactorModel?>()
        coEvery { profileRepository.load(any()) } coAnswers { loaded.await() }
        val page = com.difft.android.login.ContactProfileSettingFragment.newInstance(com.difft.android.login.ContactProfileSettingFragment.FROM_CONTACT)
        show(page)
        compose.onNode(hasSetTextAction()).performTextReplacement("typed")
        controller.recreate()
        loaded.complete(org.difft.app.database.models.ContactorModel().apply { id = "self"; name = "old" })
        val after = ViewModelProvider(restored())[com.difft.android.login.mvi.ProfileEditViewModel::class.java]
        assertEquals("typed", after.state.value.nameDraft)
        assertEquals("typed", restored().requireView().findViewById<com.difft.android.base.widget.DifftClearableInputView>(com.difft.android.login.R.id.et_name).query)
    }

    @Test fun `custom id fragment preserves edited input across real activity recreation`() {
        val page = SetCustomIdFragment.newInstance("initial")
        show(page)
        val model = ViewModelProvider(page)[SetCustomIdViewModel::class.java]
        model.dispatch(SetCustomIdContract.Intent.ChangeInput("edited_name"))
        controller.recreate()
        val after = ViewModelProvider(restored())[SetCustomIdViewModel::class.java]
        assertEquals("edited_name", after.state.value.input)
    }

    @Test fun `custom id rejecting validation disables actual save button`() {
        val page = SetCustomIdFragment.newInstance("initial")
        show(page)
        val model = ViewModelProvider(page)[SetCustomIdViewModel::class.java]
        model.dispatch(SetCustomIdContract.Intent.ChangeInput("1invalid"))
        compose.onNodeWithText(controller.get().getString(com.difft.android.R.string.settings_contact_ID_button_save)).assertIsNotEnabled()
        model.dispatch(SetCustomIdContract.Intent.Save)
        assertEquals("1invalid", model.state.value.input)
        assertEquals(false, model.state.value.busy)
    }

    private fun customIdPage(): SetCustomIdFragment = SetCustomIdFragment.newInstance("stored_name").also(::show)
    private fun enterCustomId(value: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(value)
    }
    private fun clickCustomIdSave() {
        compose.onNodeWithText(controller.get().getString(com.difft.android.R.string.settings_contact_ID_button_save)).performClick()
    }
    private fun customIdModel(page: Fragment) = ViewModelProvider(page)[SetCustomIdViewModel::class.java]

    @Test fun `valid custom id submits once while busy through the actual save button`() {
        val response = CompletableDeferred<BaseResponse<GetProfileResponse>>()
        coEvery { settings.setProfile(customUid = "candidate_id") } coAnswers { response.await() }
        val page = customIdPage()
        enterCustomId("candidate_id")
        clickCustomIdSave()
        val model = customIdModel(page)
        assertEquals(true, model.state.value.busy)
        compose.onNodeWithText(controller.get().getString(com.difft.android.R.string.settings_contact_ID_button_save)).assertIsNotEnabled()
        clickCustomIdSave()
        model.dispatch(SetCustomIdContract.Intent.Save)
        coVerify(exactly = 1) { settings.setProfile(customUid = "candidate_id") }
        assertEquals("stored_name", users.getUserData()?.customUid)
    }

    @Test fun `rejected custom id keeps actual input and allows one new retry`() {
        val rejected = CompletableDeferred<BaseResponse<GetProfileResponse>>()
        val retry = CompletableDeferred<BaseResponse<GetProfileResponse>>()
        var calls = 0
        coEvery { settings.setProfile(customUid = "candidate_id") } coAnswers {
            if (calls++ == 0) rejected.await() else retry.await()
        }
        val page = customIdPage()
        enterCustomId("candidate_id")
        clickCustomIdSave()
        rejected.complete(BaseResponse(1, 5, "server rejected", null))
        val model = customIdModel(page)
        compose.waitUntil { !model.state.value.busy && model.state.value.error == "server rejected" }
        compose.onNode(hasSetTextAction()).assertTextEquals("candidate_id")
        compose.onNodeWithText("server rejected").assertExists()
        compose.onNodeWithText(controller.get().getString(com.difft.android.R.string.settings_contact_ID_button_save)).assertIsEnabled()
        assertEquals(false, model.state.value.completed)
        assertEquals("stored_name", users.getUserData()?.customUid)
        
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(
            com.difft.android.base.ui.compose.DifftButtonDefaults.ClickDebounceMillis + 1,
        ))
        clickCustomIdSave()
        assertEquals(true, model.state.value.busy)
        coVerify(exactly = 2) { settings.setProfile(customUid = "candidate_id") }
    }

    @Test fun `accepted custom id persists and completed navigation replays after hosted recreation`() {
        val response = CompletableDeferred<BaseResponse<GetProfileResponse>>()
        coEvery { settings.setProfile(customUid = "candidate_id") } coAnswers { response.await() }
        val host = SecuritySettingsLeafHost()
        show(host)
        val page = SetCustomIdFragment.newInstance("stored_name")
        host.childFragmentManager.beginTransaction().replace(SecuritySettingsLeafHost.CONTENT_ID, page, "leaf").commitNow()
        enterCustomId("candidate_id")
        clickCustomIdSave()
        response.complete(BaseResponse(1, 0, null, null))
        compose.waitUntil { customIdModel(page).state.value.completed }
        assertEquals("candidate_id", users.getUserData()?.customUid)
        verify(atLeast = 1) { host.detailNavigation.pop(page) }
        
        controller.recreate()
        val restoredHost = restored() as SecuritySettingsLeafHost
        val restoredPage = requireNotNull(restoredHost.childFragmentManager.findFragmentByTag("leaf"))
        compose.waitForIdle()
        assertEquals(true, customIdModel(restoredPage).state.value.completed)
        assertEquals(false, customIdModel(restoredPage).state.value.busy)
        assertEquals("candidate_id", customIdModel(restoredPage).state.value.input)
        assertEquals("candidate_id", users.getUserData()?.customUid)
        verify(atLeast = 1) { restoredHost.detailNavigation.pop(restoredPage) }
        customIdModel(restoredPage).dispatch(SetCustomIdContract.Intent.Save)
        coVerify(exactly = 1) { settings.setProfile(customUid = "candidate_id") }
    }

    @Test fun `old account custom id response cannot change the new account`() {
        val response = CompletableDeferred<BaseResponse<GetProfileResponse>>()
        coEvery { settings.setProfile(customUid = "candidate_id") } coAnswers { response.await() }
        val page = customIdPage()
        enterCustomId("candidate_id")
        clickCustomIdSave()
        every { globals.mockGlobalServices.myId } returns "new-user"
        users.update { account = "new-user"; baseAuth = "new-authorization"; customUid = "new_user_id" }
        response.complete(BaseResponse(1, 0, null, null))
        val model = customIdModel(page)
        compose.waitUntil { !model.state.value.busy }
        assertEquals("new-user", users.getUserData()?.account)
        assertEquals("new_user_id", users.getUserData()?.customUid)
        assertEquals(false, model.state.value.completed)
        coVerify(exactly = 1) { settings.setProfile(customUid = "candidate_id") }
    }

    @Test fun `rotated authorization rejects an old custom id response for the same account`() {
        val response = CompletableDeferred<BaseResponse<GetProfileResponse>>()
        coEvery { settings.setProfile(customUid = "candidate_id") } coAnswers { response.await() }
        val page = customIdPage()
        enterCustomId("candidate_id")
        clickCustomIdSave()
        users.update { baseAuth = "replacement-authorization"; customUid = "replacement_id" }
        response.complete(BaseResponse(1, 0, null, null))
        val model = customIdModel(page)
        compose.waitUntil { !model.state.value.busy }
        assertEquals("replacement_id", users.getUserData()?.customUid)
        assertEquals(false, model.state.value.completed)
    }

    @Test fun `removing the custom id page cancels its pending request before local completion`() {
        val response = CompletableDeferred<BaseResponse<GetProfileResponse>>()
        val canceled = CompletableDeferred<Unit>()
        coEvery { settings.setProfile(customUid = "candidate_id") } coAnswers {
            try { response.await() } finally { canceled.complete(Unit) }
        }
        val page = customIdPage()
        enterCustomId("candidate_id")
        clickCustomIdSave()
        controller.get().supportFragmentManager.beginTransaction().remove(page).commitNow()
        compose.waitUntil { canceled.isCompleted }
        response.complete(BaseResponse(1, 0, null, null))
        assertEquals("stored_name", users.getUserData()?.customUid)
        coVerify(exactly = 1) { settings.setProfile(customUid = "candidate_id") }
    }

    @Test fun `timeout option click stores the value and resume refresh reads changed truth`() {
        val page = SetPasscodeTimeoutFragment()
        show(page)
        val model = ViewModelProvider(page)[PasscodeTimeoutViewModel::class.java]
        assertEquals(60, model.state.value.selected)
        compose.onNodeWithText(passcodeTimeoutLabel(controller.get(), 300)).performClick()
        assertEquals(300, users.getUserData()?.passcodeTimeout)
        assertEquals(300, model.state.value.selected)
        controller.pause().stop()
        users.update { passcodeTimeout = 0 }
        assertEquals(300, model.state.value.selected)
        controller.start().resume().visible()
        compose.waitForIdle()
        assertEquals(0, model.state.value.selected)
        assertEquals(0, users.getUserData()?.passcodeTimeout)
    }

    @Test fun `invalid timeout intents leave the actual model and user store unchanged`() {
        val page = SetPasscodeTimeoutFragment()
        show(page)
        val model = ViewModelProvider(page)[PasscodeTimeoutViewModel::class.java]
        val before = model.state.value
        
        listOf(-1, 1, Int.MAX_VALUE).forEach { seconds ->
            model.dispatch(PasscodeTimeoutContract.Intent.Select(seconds))
            assertEquals(before, model.state.value)
            assertEquals(60, users.getUserData()?.passcodeTimeout)
        }
    }

    @Test fun `lock fragment preserves pending external stage across real activity recreation`() {
        val page = ScreenLockSettingFragment()
        show(page)
        val model = ViewModelProvider(page)[ScreenLockSettingsViewModel::class.java]
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Pattern, true))
        
        val pending = requireNotNull(model.state.value.pending)
        controller.recreate()
        val after = ViewModelProvider(restored())[ScreenLockSettingsViewModel::class.java]
        assertEquals(pending.requestId, after.state.value.pending?.requestId)
        assertEquals(pending.stage, after.state.value.pending?.stage)
        assertNull(after.takeVisibleLaunch())
    }

    @Test fun `delete fragment restores confirmation input and timeout fragment inflates actual body`() {
        val page = DeleteAccountFragment()
        show(page)
        val model = ViewModelProvider(page)[DeleteAccountViewModel::class.java]
        model.dispatch(DeleteAccountContract.Intent.ChangeInput("draft"))
        controller.recreate()
        val after = ViewModelProvider(restored())[DeleteAccountViewModel::class.java]
        assertEquals("draft", after.state.value.input)
        show(SetPasscodeTimeoutFragment())
    }
}

@AndroidEntryPoint
class SecuritySettingsTestActivity : FragmentActivity()


class SecuritySettingsLeafHost : Fragment(), DetailStackHost {
    companion object { const val CONTENT_ID = 0x12304567 }
    override val detailNavigation: DetailNavigation = mockk(relaxed = true) {
        every { pop(any()) } returns true
    }
    override fun detailBackOwnerFor(fragment: Fragment): LifecycleOwner = viewLifecycleOwner
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: android.os.Bundle?,
    ): android.view.View = android.widget.FrameLayout(requireContext()).apply { id = CONTENT_ID }
}
