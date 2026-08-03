package com.difft.android.linkeddevices

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.LogoutManager
import com.difft.android.chat.invite.ScanActivity
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.test.TestDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The "Link New Device" row launches [ScanActivity]. Uses createAndroidComposeRule so the host
 * activity is reachable and the started intent can be asserted via [shadowOf].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class LinkedDevicesScanNavTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val repo: DeviceRepository = mockk()
    private val logoutManager: LogoutManager = mockk(relaxed = true)
    private val countStore: LinkedDevicesCountStore = mockk(relaxed = true)

    @Test
    fun `link new device row launches ScanActivity`() {
        coEvery { repo.getDevices() } returns emptyList()
        val vm = LinkedDevicesViewModel(repo, logoutManager, countStore)
        composeTestRule.setContent {
            DifftTheme { LinkedDevicesScreen(vm, showBackButton = true, onBack = {}) }
        }
        vm.refresh()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Link New Device").performClick()
        composeTestRule.waitForIdle()

        val intent = shadowOf(composeTestRule.activity).nextStartedActivity
        assertNotNull(intent, "Link New Device should start an activity")
        assertEquals(ScanActivity::class.java.name, intent.component?.className)
    }
}
