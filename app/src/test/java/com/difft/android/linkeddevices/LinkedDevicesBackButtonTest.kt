package com.difft.android.linkeddevices

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.fragment.app.Fragment
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.utils.DualPaneUtils
import com.difft.android.me.FakeDualPaneHostActivity
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.test.TestDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The TitleBar back button is hidden in tablet dual-pane mode and shown otherwise. Verifies both the
 * screen's rendering of the showBackButton param and the host's `!isInDualPaneMode()` mapping that
 * feeds it (the real @AndroidEntryPoint fragment can't be launched without a Hilt harness).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class LinkedDevicesBackButtonTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    @get:Rule
    val composeTestRule = createComposeRule()

    private val repo: DeviceRepository = mockk()
    private val logoutManager: LogoutManager = mockk(relaxed = true)
    private val countStore: LinkedDevicesCountStore = mockk(relaxed = true)

    private fun buildViewModel() = LinkedDevicesViewModel(repo, logoutManager, countStore)

    @Test
    fun `I9 dual-pane hides the back button`() {
        coEvery { repo.getDevices() } returns emptyList()
        val vm = buildViewModel()
        composeTestRule.setContent {
            DifftTheme { LinkedDevicesScreen(vm, showBackButton = false, onBack = {}) }
        }
        vm.refresh()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `single-pane shows the back button`() {
        coEvery { repo.getDevices() } returns emptyList()
        val vm = buildViewModel()
        composeTestRule.setContent {
            DifftTheme { LinkedDevicesScreen(vm, showBackButton = true, onBack = {}) }
        }
        vm.refresh()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    // The host computes showBackButton = !isInDualPaneMode(); confirm that mapping against a real host.
    @Test
    fun `isInDualPaneMode drives the back-button visibility flag`() {
        assertFalse(showBackButtonFor(dualPane = true)) // dual-pane -> back hidden
        assertTrue(showBackButtonFor(dualPane = false)) // single-pane -> back shown
    }

    /** Attaches a bare fragment to a [FakeDualPaneHostActivity] and returns `!isInDualPaneMode()`. */
    private fun showBackButtonFor(dualPane: Boolean): Boolean {
        val controller = Robolectric.buildActivity(FakeDualPaneHostActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        return try {
            controller.get().dualPane = dualPane
            val fragment = Fragment()
            controller.get().supportFragmentManager.beginTransaction()
                .add(fragment, "probe").commitNow()
            with(DualPaneUtils) { !fragment.isInDualPaneMode() }
        } finally {
            runCatching { controller.destroy() }
        }
    }
}
