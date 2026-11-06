package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.pane.DetailNavigation
import com.difft.android.base.utils.pane.DetailStackHost
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.DifftClearableInputView
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.login.BindAccountActivity
import com.difft.android.login.ContactProfileSettingFragment
import com.difft.android.login.RandomNameUtil
import com.difft.android.login.ScreenLockActivity
import com.difft.android.login.SecurityResultNonce
import com.difft.android.login.data.AccountData
import com.difft.android.login.repo.ProfileSaveRepository
import com.difft.android.login.repo.ProfileSession
import com.difft.android.login.viewmodel.LoginViewModel
import com.difft.android.network.viewmodel.Resource
import com.difft.android.setting.mvi.DeleteAccountContract
import com.difft.android.setting.mvi.DeleteAccountViewModel
import com.difft.android.setting.mvi.ScreenLockContract
import com.difft.android.setting.mvi.ScreenLockSettingsViewModel
import com.difft.android.setting.repo.AccountMutationRepository
import com.difft.android.setting.repo.AccountMutationResult
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import com.difft.android.test.FoldableTestApplication_Application
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import javax.inject.Inject
import kotlin.test.*

/** Real Fragment launcher registration and lifecycle; only transport/application boundaries are mocked. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = FoldableTestApplication_Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecurityFlowResultsIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule()
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @BindValue @JvmField val profile: ProfileSaveRepository = mockk(relaxed = true)
    @BindValue @JvmField val accounts: AccountMutationRepository = mockk(relaxed = true)
    @BindValue @JvmField val groups: com.difft.android.network.group.GroupRepo = mockk(relaxed = true)
    @BindValue @JvmField val strictUsers: StorageBoundUserManager = mockk(relaxed = true)
    @BindValue @JvmField val identity: com.difft.android.setting.repo.IdentityRotationRepository = mockk(relaxed = true)
    @BindValue @JvmField val logoutBoundary: LogoutManagerImpl = mockk(relaxed = true)
    @Inject lateinit var users: UserManager
    private val session = mockk<ProfileSession> { every { accountId } returns "test-user-001" }
    private lateinit var controller: ActivityController<SecuritySettingsTestActivity>
    private val liveWaits = mutableSetOf<Int>()
    private var token = 0
    private var confirm: (() -> Unit)? = null
    private var generated: java.io.File? = null

    @Before fun setup() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        hilt.inject()
        users.update { account = "test-user-001"; email = "masked"; phoneNumber = null; pattern = null; passcode = "existing" }
        every { profile.captureSession() } returns session
        coEvery { profile.load(any()) } returns null
        coEvery { profile.save(any(), any(), any(), any()) } returns "accepted"
        coEvery { accounts.refresh() } just Runs
        mockkObject(ComposeDialogManager, ToastUtil)
        io.mockk.mockkStatic(ComposeDialogManager::class)
        every { ToastUtil.show(any<Int>()) } just Runs
        every { ToastUtil.show(any<String>()) } just Runs
        every { ToastUtil.showLong(any<String>()) } just Runs
        every { ComposeDialogManager.showWait(any(), any(), any(), any(), any(), any()) } answers { (++token).also { liveWaits += it } }
        every { ComposeDialogManager.isWaitShowing(any()) } answers { firstArg<Int>() in liveWaits }
        every { ComposeDialogManager.dismissWait(any<Int>()) } answers { liveWaits -= firstArg<Int>(); Unit }
        every {
            ComposeDialogManager.showMessageDialog(
                context = any(), title = any(), message = any(), confirmText = any(), cancelText = any(),
                showCancel = any(), cancelable = any(), autoDismiss = any(), confirmButtonColor = any(),
                cancelButtonColor = any(), onConfirm = any(), onCancel = any(), onDismiss = any(), onClosed = any(), content = any(),
            )
        } answers { confirm = arg(10); mockk<ComposeDialog>(relaxed = true) }
        controller = Robolectric.buildActivity(SecuritySettingsTestActivity::class.java).setup()
    }

    @After fun cleanup() {
        controller.pause().stop().destroy()
        generated?.delete()
        io.mockk.unmockkStatic(ComposeDialogManager::class)
        io.mockk.unmockkStatic(com.bumptech.glide.Glide::class)
        unmockkObject(ComposeDialogManager, ToastUtil, AvatarUtil, RandomNameUtil)
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun show(page: Fragment): SecurityFlowHost {
        val host = SecurityFlowHost()
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, host, "host").commitNow()
        host.childFragmentManager.beginTransaction().replace(SecurityFlowHost.CONTENT, page, "page").commitNow()
        idle()
        return host
    }
    private fun lockModel(page: Fragment) = ViewModelProvider(page)[ScreenLockSettingsViewModel::class.java]
    private fun nextLaunch() = requireNotNull(shadowOf(controller.get()).nextStartedActivityForResult)
    private fun result(requestCode: Int, code: Int, requestId: String?) {
        controller.get().activityResultRegistry.dispatchResult(requestCode, code,
            Intent().putExtra(SecurityResultNonce.EXTRA_REQUEST_ID, requestId))
        idle()
    }

    @Test fun `hidden verification result waits for visible setup and canceled setup rereads truth`() {
        val page = ScreenLockSettingFragment()
        val host = show(page)
        val model = lockModel(page)
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Pattern, true))
        idle()
        val verify = nextLaunch()
        assertEquals(ScreenLockActivity::class.java.name, verify.intent.component?.className)
        val nonce = verify.intent.getStringExtra(SecurityResultNonce.EXTRA_REQUEST_ID)
        host.visible(false)
        result(verify.requestCode, Activity.RESULT_OK, nonce)
        assertEquals(ScreenLockContract.Stage.ReadyForSetup, model.state.value.pending?.stage)
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        host.visible(true)
        idle()
        val setup = nextLaunch()
        assertEquals(SetPatternActivity::class.java.name, setup.intent.component?.className)
        users.update { pattern = "persisted-pattern" }
        result(setup.requestCode, Activity.RESULT_CANCELED, nonce)
        assertTrue(model.state.value.pattern)
        assertNull(model.state.value.pending)
    }

    @Test fun `actual verification cancellation cannot disable stored lock`() {
        val page = ScreenLockSettingFragment()
        show(page)
        val model = lockModel(page)
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Passcode, false))
        idle()
        val launch = nextLaunch()
        result(launch.requestCode, Activity.RESULT_CANCELED, launch.intent.getStringExtra(SecurityResultNonce.EXTRA_REQUEST_ID))
        assertEquals("existing", users.getUserData()?.passcode)
        assertNull(model.state.value.pending)
    }

    @Test fun `actual successful verification disables lock while its owner is hidden`() {
        val page = ScreenLockSettingFragment()
        val host = show(page)
        val model = lockModel(page)
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Passcode, false))
        idle()
        val launch = nextLaunch()
        host.visible(false)
        result(launch.requestCode, Activity.RESULT_OK, launch.intent.getStringExtra(SecurityResultNonce.EXTRA_REQUEST_ID))
        assertNull(users.getUserData()?.passcode)
        assertFalse(model.state.value.passcode)
        assertNull(model.state.value.pending)
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
    }

    @Test fun `actual old account verification result cannot mutate replacement state`() {
        val page = ScreenLockSettingFragment()
        show(page)
        val model = lockModel(page)
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Passcode, false))
        idle()
        val launch = nextLaunch()
        every { globals.mockGlobalServices.myId } returns "replacement"
        users.update { account = "replacement"; passcode = "replacement-lock" }
        result(launch.requestCode, Activity.RESULT_OK, launch.intent.getStringExtra(SecurityResultNonce.EXTRA_REQUEST_ID))
        assertEquals("replacement-lock", users.getUserData()?.passcode)
        assertNull(model.state.value.pending)
    }

    @Test fun `actual nonce mismatch cannot authorize disabling a lock`() {
        val page = ScreenLockSettingFragment()
        show(page)
        val model = lockModel(page)
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Passcode, false))
        idle()
        val launch = nextLaunch()
        result(launch.requestCode, Activity.RESULT_OK, "different-request")
        assertEquals("existing", users.getUserData()?.passcode)
        assertEquals(ScreenLockContract.Stage.AwaitingVerification, model.state.value.pending?.stage)
    }

    @Test fun `actual binding result launches setup only after visibility resumes`() {
        users.update { passcode = null; email = null }
        val page = ScreenLockSettingFragment()
        val host = show(page)
        val model = lockModel(page)
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Pattern, true))
        model.dispatch(ScreenLockContract.Intent.ChooseBinding(BindAccountActivity.TYPE_BIND_EMAIL))
        idle()
        val bind = nextLaunch()
        assertEquals(BindAccountActivity::class.java.name, bind.intent.component?.className)
        users.update { email = "masked" }
        host.visible(false)
        result(bind.requestCode, Activity.RESULT_OK, bind.intent.getStringExtra(SecurityResultNonce.EXTRA_REQUEST_ID))
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        host.visible(true)
        idle()
        assertEquals(SetPatternActivity::class.java.name, nextLaunch().intent.component?.className)
    }

    @Test fun `actual binding cancellation clears pending without enabling a lock`() {
        users.update { passcode = null; email = null }
        val page = ScreenLockSettingFragment()
        show(page)
        val model = lockModel(page)
        model.dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Pattern, true))
        model.dispatch(ScreenLockContract.Intent.ChooseBinding(BindAccountActivity.TYPE_BIND_EMAIL))
        idle()
        val bind = nextLaunch()
        result(bind.requestCode, Activity.RESULT_CANCELED, bind.intent.getStringExtra(SecurityResultNonce.EXTRA_REQUEST_ID))
        assertNull(model.state.value.pending)
        assertFalse(model.state.value.pattern)
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
    }

    @Test fun `logout wait releases while hidden and before its logout boundary`() {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        coEvery { accounts.logout(any()) } coAnswers {
            val release = firstArg<() -> Unit>()
            gate.await()
            release()
            events += if (liveWaits.isEmpty()) "released-before-logout" else "leaked"
            AccountMutationResult.Applied
        }
        val page = AccountFragment()
        val host = show(page)
        page.requireView().findViewById<View>(com.difft.android.R.id.cl_logout).performClick()
        requireNotNull(confirm).invoke()
        idle()
        assertTrue(liveWaits.isNotEmpty())
        host.visible(false)
        idle()
        assertTrue(liveWaits.isEmpty())
        host.visible(true)
        idle()
        assertTrue(liveWaits.isNotEmpty())
        gate.complete(Unit)
        idle()
        assertEquals(listOf("released-before-logout"), events)
    }

    @Test fun `delete wait hides with page and view reconstruction never remounts its modal`() {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        coEvery { accounts.deleteAccount(any()) } coAnswers {
            val release = firstArg<() -> Unit>()
            gate.await()
            release()
            events += if (liveWaits.isEmpty()) "released-before-logout" else "leaked"
            AccountMutationResult.Applied
        }
        val page = DeleteAccountFragment()
        val host = show(page)
        val model = ViewModelProvider(page)[DeleteAccountViewModel::class.java]
        compose.onNode(hasSetTextAction()).performTextInput(model.state.value.suffix)
        compose.onNodeWithText(controller.get().getString(com.difft.android.chat.R.string.me_delete)).performClick()
        requireNotNull(confirm).invoke()
        idle()
        assertTrue(liveWaits.isNotEmpty())
        host.visible(false)
        idle()
        assertTrue(liveWaits.isEmpty())
        host.childFragmentManager.beginTransaction().detach(page).commitNow()
        host.childFragmentManager.beginTransaction().attach(page).commitNow()
        host.visible(true)
        idle()
        assertTrue(liveWaits.isEmpty())
        gate.complete(Unit)
        idle()
        assertEquals(listOf("released-before-logout"), events)
    }

    private fun prepareGeneratedProfile() {
        io.mockk.mockkStatic(com.bumptech.glide.Glide::class)
        every { com.bumptech.glide.Glide.with(any<View>()) } returns mockk(relaxed = true)
        mockkObject(AvatarUtil, RandomNameUtil)
        generated = java.io.File(controller.get().cacheDir, "profile-generated.png").apply {
            outputStream().use { stream -> android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream) }
        }
        every { AvatarUtil.generateRandomAvatarFile() } returns requireNotNull(generated).absolutePath
        every { RandomNameUtil.getRandomName(any()) } returns "Generated"
    }
    private fun profilePage(mode: Int): ContactProfileSettingFragment {
        val page = ContactProfileSettingFragment.newInstance(mode)
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, page, "profile").commitNow()
        return page
    }

    @Test fun `register fragment uses shared saver then routes to Index and closes shell`() {
        prepareGeneratedProfile()
        val page = profilePage(ContactProfileSettingFragment.FROM_REGISTER)
        compose.waitUntil(5_000) { page.requireView().findViewById<DifftClearableInputView>(com.difft.android.login.R.id.et_name).query.isNotBlank() }
        page.requireView().findViewById<View>(com.difft.android.login.R.id.btn_done).performClick()
        idle()
        coVerify(exactly = 1) { profile.save(any(), "Generated", null, session) }
        assertEquals("com.difft.android.IndexActivity", shadowOf(controller.get()).nextStartedActivity?.component?.className)
        assertTrue(controller.get().isFinishing)
    }

    @Test fun `signup fragment sign-in boundary invokes shared saver then closes shell for Index`() {
        prepareGeneratedProfile()
        val page = profilePage(ContactProfileSettingFragment.FROM_SIGN_UP)
        compose.waitUntil(5_000) { page.requireView().findViewById<DifftClearableInputView>(com.difft.android.login.R.id.et_name).query.isNotBlank() }
        val login = ViewModelProvider(page)[LoginViewModel::class.java]
        @Suppress("UNCHECKED_CAST")
        (login.signInLiveData as MutableLiveData<Resource<AccountData>>).value = Resource.success(AccountData(true))
        idle()
        coVerify(exactly = 1) { profile.save(any(), "Generated", null, session) }
        assertEquals("com.difft.android.IndexActivity", shadowOf(controller.get()).nextStartedActivity?.component?.className)
        assertTrue(controller.get().isFinishing)
    }

    @Test fun `hosted contact fragment saves through same repository and keeps its Index owner open`() {
        val page = ContactProfileSettingFragment.newInstance(ContactProfileSettingFragment.FROM_CONTACT)
        val host = show(page)
        compose.onNode(hasSetTextAction()).performTextReplacement("Edited")
        page.requireView().findViewById<View>(com.difft.android.login.R.id.btn_done).performClick()
        idle()
        coVerify(exactly = 1) { profile.save(null, "Edited", null, session) }
        assertFalse(controller.get().isFinishing)
        verify(exactly = 0) { host.detailNavigation.pop(any()) }
        assertNull(shadowOf(controller.get()).nextStartedActivity)
    }
}

class SecurityFlowHost : Fragment(), DetailStackHost {
    companion object { const val CONTENT = 0x012300ff }
    private val visibleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    override val detailNavigation: DetailNavigation = mockk(relaxed = true)
    override fun detailBackOwnerFor(fragment: Fragment): LifecycleOwner = visibleOwner
    fun visible(value: Boolean) { visibleOwner.registry.currentState = if (value) Lifecycle.State.STARTED else Lifecycle.State.CREATED }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = CONTENT; visible(true) }
    override fun onDestroyView() { visibleOwner.registry.currentState = Lifecycle.State.DESTROYED; super.onDestroyView() }
}
