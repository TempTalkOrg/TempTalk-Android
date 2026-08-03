package com.difft.android.linkeddevices

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.difft.android.R
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.test.TestDispatcherRule
import com.difft.android.websocket.api.messages.multidevice.DeviceInfo
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

/**
 * Compose interaction tests for [LinkedDevicesScreen]: real screen + real ViewModel with a mocked
 * [DeviceRepository] (the project ships no Hilt test harness, so the VM is built directly).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class LinkedDevicesScreenTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    @get:Rule
    val composeTestRule = createComposeRule()

    private val repo: DeviceRepository = mockk()
    private val logoutManager: LogoutManager = mockk(relaxed = true)
    private val countStore: LinkedDevicesCountStore = mockk(relaxed = true)

    private fun buildViewModel() = LinkedDevicesViewModel(repo, logoutManager, countStore)

    private fun setScreen(vm: LinkedDevicesViewModel) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        composeTestRule.setContent {
            DifftTheme { LinkedDevicesScreen(vm, showBackButton = true, onBack = {}) }
        }
    }

    // I1
    @Test
    fun `renders device rows and link new row`() {
        coEvery { repo.getDevices() } returns listOf(
            DeviceInfo(id = 2, name = "iPad Pro", created = 1_700_000_000_000L, lastSeen = 1_710_000_000_000L),
        )
        val vm = buildViewModel()
        setScreen(vm)
        vm.refresh()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("iPad Pro").assertIsDisplayed()
        composeTestRule.onNodeWithText("Link New Device").assertIsDisplayed()
    }

    // Empty list renders only the Link New Device card, no caption, no device rows.
    @Test
    fun `renders no device rows and only link new row when empty`() {
        coEvery { repo.getDevices() } returns emptyList()
        val vm = buildViewModel()
        setScreen(vm)
        vm.refresh()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No linked devices.").assertDoesNotExist()
        composeTestRule.onNodeWithText("Link New Device").assertIsDisplayed()
    }

    // I3
    @Test
    fun `long press opens unlink menu`() {
        coEvery { repo.getDevices() } returns listOf(
            DeviceInfo(id = 2, name = "iPad Pro", created = 1_700_000_000_000L, lastSeen = 1_710_000_000_000L),
        )
        val vm = buildViewModel()
        setScreen(vm)
        vm.refresh()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("iPad Pro").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Unlink").assertIsDisplayed()
    }

    // An unlink failure toasts (via the event collector) and leaves the list intact — no optimistic
    // delete. ToastUtil is mocked because its Application-context toast can't render under Robolectric.
    @Test
    fun `unlink failure shows toast and leaves list unchanged`() {
        coEvery { repo.getDevices() } returns listOf(
            DeviceInfo(id = 2, name = "iPad Pro", created = 1_700_000_000_000L, lastSeen = 1_710_000_000_000L),
        )
        coEvery { repo.removeDevice(2) } throws RuntimeException("boom")
        val vm = buildViewModel()
        setScreen(vm)
        vm.refresh()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("iPad Pro").assertIsDisplayed()

        mockkObject(ToastUtil)
        try {
            // The confirm dialog is a View-world ComposeView (out of this screen's composition); its
            // onConfirm delegates straight to vm.unlink(id), driven here directly.
            vm.unlink(2)
            composeTestRule.waitForIdle()
            verify(exactly = 1) { ToastUtil.show(R.string.linked_devices_unlink_failed) }
            composeTestRule.onNodeWithText("iPad Pro").assertIsDisplayed()
        } finally {
            unmockkObject(ToastUtil)
        }
    }

    // A fetch failure never hides the Link New Device entry; it surfaces a one-shot toast instead.
    @Test
    fun `fetch failure keeps link new row and shows toast`() {
        coEvery { repo.getDevices() } throws RuntimeException("boom")
        val vm = buildViewModel()
        setScreen(vm)

        mockkObject(ToastUtil)
        try {
            vm.refresh()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Link New Device").assertIsDisplayed()
            verify(exactly = 1) { ToastUtil.show(R.string.linked_devices_list_update_failed) }
        } finally {
            unmockkObject(ToastUtil)
        }
    }
}
