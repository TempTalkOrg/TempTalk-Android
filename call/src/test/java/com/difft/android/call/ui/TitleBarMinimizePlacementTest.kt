package com.difft.android.call.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.difft.android.base.call.CallType
import com.difft.android.base.user.CallConfig
import com.difft.android.call.CallIntent
import com.difft.android.call.data.CallStatus
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.service.TestScopeApplication
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs






@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [30])
class TitleBarMinimizePlacementTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun setTitleBar() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.JOIN_CALL, CallType.GROUP.type)
        CallVmTestHarness.stubCallStatus(CallStatus.JOINING, CallType.GROUP.type)
        val viewModel = CallVmTestHarness.buildViewModel(callIntent)
        CallVmTestHarness.mockEntryPointAccessors(mockk<ContactorCacheManager>(relaxed = true))
        composeTestRule.setContent {
            MainPageWithTopStatusView(
                viewModel = viewModel,
                isInPipMode = false,
                isOneVOneCall = false,
                isUserSharingScreen = false,
                callConfig = CallConfig(),
                callIntent = callIntent,
                windowZoomOutAction = {},
                onE2eeHintClick = {},
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun zoomOutCount() =
        composeTestRule.onAllNodesWithTag("call_topbar_zoom_out", useUnmergedTree = true).fetchSemanticsNodes().size

    private fun assertTitleCentred() {
        val root = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        val title = composeTestRule.onNodeWithTag("call_topbar_status_click_target", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val rootCentre = (root.left + root.right).value / 2
        val titleCentre = (title.left + title.right).value / 2
        assertTrue("title centre $titleCentre vs window centre $rootCentre", abs(rootCentre - titleCentre) <= 1f)
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-port-xhdpi")
    fun `bottom bar window keeps minimize in the title bar`() {
        setTitleBar()
        assertEquals(1, zoomOutCount())
        assertTitleCentred()
    }

    @Test
    @Config(qualifiers = "w860dp-h608dp-land-xhdpi")
    fun `rail window drops the title-bar minimize and keeps the title centred`() {
        setTitleBar()
        assertEquals(0, zoomOutCount())
        assertTitleCentred()
    }
}
