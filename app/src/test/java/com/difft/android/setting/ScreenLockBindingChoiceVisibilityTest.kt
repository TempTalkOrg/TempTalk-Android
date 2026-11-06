package com.difft.android.setting

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.pane.DetailNavigation
import com.difft.android.base.utils.pane.DetailStackHost
import com.difft.android.setting.mvi.ScreenLockContract
import com.difft.android.setting.mvi.ScreenLockSettingsViewModel
import com.difft.android.setting.repo.SettingRepo
import com.difft.android.test.FoldableTestApplication_Application
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.every
import io.mockk.mockk
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

/**
 * The enable-lock binding choice ([ScreenLockContract.Stage.ChoosingBinding]) belongs to the ViewModel:
 * a fold, rotation or tab switch only unmounts its dialog, and the choice returns with the visible top.
 * Only the user's own dismiss cancels it (PR #1461 review).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = FoldableTestApplication_Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenLockBindingChoiceVisibilityTest {
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
    private lateinit var controller: ActivityController<ScreenLockVisibilityTestActivity>

    @Before fun setup() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        hilt.inject()
        // No lock yet and nothing bound: enabling a lock must first ask for an email or phone binding.
        users.update { account = "test-user-001"; email = null; phoneNumber = null; pattern = null; passcode = null }
        controller = Robolectric.buildActivity(ScreenLockVisibilityTestActivity::class.java).setup().visible()
    }
    @After fun teardown() { controller.pause().stop().destroy() }

    private fun bindEmailChoice() = compose.onNodeWithText(controller.get().getString(com.difft.android.login.R.string.login_bind_email))
    private fun settle() { shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle() }
    private fun model(page: Fragment) = ViewModelProvider(page)[ScreenLockSettingsViewModel::class.java]

    @Test fun `covering the page keeps the binding choice and shows it again on return, after recreation, until the user dismisses it`() {
        val host = ScreenLockVisibilityHost()
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, host, "host").commitNow()
        val page = ScreenLockSettingFragment()
        host.childFragmentManager.beginTransaction().replace(ScreenLockVisibilityHost.CONTENT, page, "lock").commitNow()
        model(page).dispatch(ScreenLockContract.Intent.Toggle(ScreenLockContract.Kind.Pattern, true))
        settle()
        assertEquals(ScreenLockContract.Stage.ChoosingBinding, model(page).state.value.pending?.stage)
        bindEmailChoice().assertExists()

        host.leaveVisibleTop(page)
        settle()
        bindEmailChoice().assertDoesNotExist()
        assertEquals(ScreenLockContract.Stage.ChoosingBinding, model(page).state.value.pending?.stage)

        host.returnToVisibleTop(page)
        settle()
        assertEquals(ScreenLockContract.Stage.ChoosingBinding, model(page).state.value.pending?.stage)
        bindEmailChoice().assertExists()

        controller.recreate()
        settle()
        val restoredHost = controller.get().supportFragmentManager.findFragmentByTag("host") as ScreenLockVisibilityHost
        val restored = requireNotNull(restoredHost.childFragmentManager.findFragmentByTag("lock"))
        assertEquals(ScreenLockContract.Stage.ChoosingBinding, model(restored).state.value.pending?.stage)
        bindEmailChoice().assertExists()

        model(restored).dispatch(ScreenLockContract.Intent.DismissBinding)
        settle()
        assertNull(model(restored).state.value.pending)
        bindEmailChoice().assertDoesNotExist()
    }
}

@AndroidEntryPoint
class ScreenLockVisibilityTestActivity : FragmentActivity()

/** Detail host whose per-page visible-top owner is driven by the test, with real cover transactions. */
class ScreenLockVisibilityHost : Fragment(), DetailStackHost {
    companion object { const val CONTENT = 0x012304aa }
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    override val detailNavigation: DetailNavigation = mockk(relaxed = true)
    override fun detailBackOwnerFor(fragment: Fragment): LifecycleOwner = owner
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply {
            id = CONTENT
            owner.registry.currentState = Lifecycle.State.STARTED
        }

    fun leaveVisibleTop(page: Fragment) {
        owner.registry.currentState = Lifecycle.State.CREATED
        childFragmentManager.beginTransaction()
            .setMaxLifecycle(page, Lifecycle.State.STARTED)
            .add(CONTENT, Fragment(android.R.layout.simple_list_item_1), "cover")
            .commitNow()
    }

    fun returnToVisibleTop(page: Fragment) {
        childFragmentManager.beginTransaction()
            .remove(requireNotNull(childFragmentManager.findFragmentByTag("cover")))
            .setMaxLifecycle(page, Lifecycle.State.RESUMED)
            .commitNow()
        owner.registry.currentState = Lifecycle.State.STARTED
    }

    override fun onDestroyView() {
        owner.registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroyView()
    }
}
