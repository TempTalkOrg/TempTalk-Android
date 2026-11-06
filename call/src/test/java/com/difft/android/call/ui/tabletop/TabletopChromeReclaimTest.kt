package com.difft.android.call.ui.tabletop

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.difft.android.base.call.CallType
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.CallIntent
import com.difft.android.call.core.CallUiController
import com.difft.android.call.data.CallStatus
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.ui.CallVmTestHarness
import com.difft.android.call.ui.ShowParticipantsListView
import com.difft.android.call.ui.actionbar.ActionBarSlots
import com.difft.android.call.ui.actionbar.CallTabletopDock
import com.difft.android.call.ui.actionbar.TABLETOP_CONSOLE_TAG
import com.difft.android.call.ui.actionbar.TABLETOP_DOCK_TAG
import com.difft.android.call.ui.actionbar.dockSlots
import com.difft.android.call.ui.actionbar.rememberCallChromeLayout
import com.difft.android.call.ui.actionbar.rememberCallSideRail
import com.difft.android.call.ui.actionbar.rememberChromeVisible
import com.difft.android.call.ui.actionbar.rememberKeepsChromeUnderPanels
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt
import com.difft.android.call.layout.Rect as LayoutRect

private const val TOLERANCE = 1f

/**
 * Tabletop QA follow-ups through production composables: the participants panel takes the dock
 * region below the fold over the console.
 * Fold at 430dp in a 608x860dp window: stage 0-410, dock 458-860.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [30], qualifiers = "w608dp-h860dp-port-xxhdpi")
class TabletopChromeReclaimTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun geometry(widthPx: Int, heightPx: Int, density: Float) = TabletopGeometry(
        bandTopPx = (410 * density).roundToInt(),
        bandBottomPx = (450 * density).roundToInt(),
        widthPx = widthPx,
        heightPx = heightPx,
        density = density,
    )

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot().let {
        DpRect(it.left, it.top, it.right, it.bottom)
    }

    @Test
    fun `the participants panel takes the dock region over the console, keeps its taps and closes back to the console`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.JOIN_CALL, CallType.GROUP.type)
        CallVmTestHarness.stubCallStatus(CallStatus.JOINING, CallType.GROUP.type)
        val ccm = mockk<ContactorCacheManager>(relaxed = true)
        every { ccm.participantDisplayMap } returns MutableStateFlow(emptyMap())
        val viewModel = CallVmTestHarness.buildViewModel(callIntent, contactorCacheManager = ccm)
        CallVmTestHarness.mockEntryPointAccessors(ccm)
        val ui = viewModel.callUiController
        ui.applyLayoutControls(hidden = false, docked = true)
        var rootTaps = 0
        var dockTopDp = 0f
        rule.setContent {
            DifftTheme(darkTheme = true, applyWindowBackground = false) {
                BoxWithConstraints(
                    Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { rootTaps++ },
                ) {
                    val g = geometry(constraints.maxWidth, constraints.maxHeight, LocalDensity.current.density)
                    dockTopDp = g.dockTopDp
                    CompositionLocalProvider(LocalCallTabletop provides g) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.tabletopDockRegion(g).testTag("dock_probe"))
                            ShowParticipantsListView(viewModel)
                        }
                    }
                }
            }
        }
        ui.setShowUsersEnabled(true)
        rule.waitForIdle()

        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val panel = bounds("call_participants_panel")
        assertEquals(458f, dockTopDp, TOLERANCE)
        // The side panel at the end of the dock: from the dock top to the window bottom.
        assertEquals("panel starts at the dock top", dockTopDp, panel.top.value, TOLERANCE)
        assertEquals("panel ends at the window bottom", root.bottom.value, panel.bottom.value, TOLERANCE)
        assertEquals("panel sits at the end edge", root.right.value, panel.right.value, TOLERANCE)
        assertEquals("panel keeps the side-panel width", 216f, panel.right.value - panel.left.value, TOLERANCE)
        assertTrue("panel stays inside the dock", panel.top >= bounds("dock_probe").top)

        rule.onNodeWithTag("call_participants_panel", useUnmergedTree = true)
            .performTouchInput { click(Offset(width / 2f, height - 4.dp.toPx())) }
        rule.waitForIdle()
        assertEquals("a tap inside the panel stays in it", 0, rootTaps)

        // Hiding the title bar keeps the panel, as before on the dock.
        ui.toggleOverlays()
        rule.waitForIdle()
        assertTrue(ui.showUsersEnabled.value)

        rule.onNodeWithTag("call_participants_btn_close", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertFalse(ui.showUsersEnabled.value)
        rule.onAllNodesWithTag("call_participants_panel", useUnmergedTree = true).fetchSemanticsNodes().let {
            assertTrue("closed back to the console", it.isEmpty())
        }
    }

    @Test
    fun `outside tabletop the participants panel keeps its side-panel width and full height`() {
        CallVmTestHarness.mockConstructionCollaborators()
        val callIntent = CallVmTestHarness.buildCallIntent(CallIntent.Action.JOIN_CALL, CallType.GROUP.type)
        CallVmTestHarness.stubCallStatus(CallStatus.JOINING, CallType.GROUP.type)
        val ccm = mockk<ContactorCacheManager>(relaxed = true)
        every { ccm.participantDisplayMap } returns MutableStateFlow(emptyMap())
        val viewModel = CallVmTestHarness.buildViewModel(callIntent, contactorCacheManager = ccm)
        CallVmTestHarness.mockEntryPointAccessors(ccm)
        rule.setContent {
            DifftTheme(darkTheme = true, applyWindowBackground = false) {
                ShowParticipantsListView(viewModel)
            }
        }
        viewModel.callUiController.setShowUsersEnabled(true)
        rule.waitForIdle()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val panel = bounds("call_participants_panel")
        assertEquals(216f, panel.right.value - panel.left.value, TOLERANCE)
        assertEquals(root.top.value, panel.top.value, TOLERANCE)
        assertEquals(root.bottom.value, panel.bottom.value, TOLERANCE)
    }

    private fun fakeSlots(): ActionBarSlots {
        val slot: @Composable () -> Unit = { Box(Modifier.size(56.dp).testTag("dock_slot").clickable {}) }
        return ActionBarSlots(slot, slot, slot, slot, slot, slot, slot, slot)
    }

    @Test
    @Config(qualifiers = "w860dp-h608dp-land-xxhdpi")
    fun `tabletop takes precedence over the rail`() {
        var tabletop by mutableStateOf<TabletopGeometry?>(null)
        var dock: TabletopGeometry? = null
        var rail: Any? = null
        rule.setContent {
            CompositionLocalProvider(LocalCallTabletop provides tabletop) {
                dock = rememberCallChromeLayout(isGroup = true).dock
                rail = rememberCallSideRail()
            }
        }
        rule.waitForIdle()
        assertNotNull("rail before tabletop", rail)

        val density = rule.density.density
        tabletop = geometry((860 * density).roundToInt(), (608 * density).roundToInt(), density)
        rule.waitForIdle()
        assertNotNull(dock)
        assertNull("no rail beside the dock", rail)
    }

    @Test
    fun `dock renders every control and only its console takes taps`() {
        var rootTaps = 0
        var isGroup by mutableStateOf(true)
        var placed by mutableStateOf<LayoutRect?>(null)
        rule.setContent {
            DifftTheme(darkTheme = true, applyWindowBackground = false) {
                BoxWithConstraints(
                    Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { rootTaps++ },
                ) {
                    val g = geometry(constraints.maxWidth, constraints.maxHeight, LocalDensity.current.density).copy(controls = placed)
                    CallTabletopDock(g, dockSlots(isGroup, peopleAvailable = true), fakeSlots(), micEnabled = true, isGroup = isGroup)
                }
            }
        }
        rule.onAllNodesWithTag("dock_slot", useUnmergedTree = true).assertCountEquals(8)
        // No plan rect yet: the console sits at the window bottom, centred, 368 wide.
        val console = bounds(TABLETOP_CONSOLE_TAG)
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        assertEquals(root.bottom.value, console.bottom.value, TOLERANCE)
        assertEquals(console.left.value - root.left.value, root.right.value - console.right.value, TOLERANCE)
        assertEquals(CallTabletopSpec.CONSOLE_WIDTH_DP, console.right.value - console.left.value, TOLERANCE)
        // Where the layout put it (decision 39), e.g. beside a floating window on the right.
        placed = LayoutRect(40.0, 600.0, 368.0, 232.0)
        rule.waitForIdle()
        val moved = bounds(TABLETOP_CONSOLE_TAG)
        assertEquals(40f, moved.left.value, TOLERANCE)
        assertEquals(600f, moved.top.value, TOLERANCE)
        assertEquals(832f, moved.bottom.value, TOLERANCE)

        rule.onNodeWithTag(TABLETOP_CONSOLE_TAG, useUnmergedTree = true).performTouchInput { click(Offset(4.dp.toPx(), 4.dp.toPx())) }
        rule.waitForIdle()
        assertEquals("a tap between console controls stays in the console", 0, rootTaps)

        rule.onNodeWithTag(TABLETOP_DOCK_TAG, useUnmergedTree = true).performTouchInput { click(Offset(10.dp.toPx(), 10.dp.toPx())) }
        rule.waitForIdle()
        assertEquals("a tap on the empty dock strip reaches the root", 1, rootTaps)

        isGroup = false
        rule.waitForIdle()
        rule.onAllNodesWithTag("dock_slot", useUnmergedTree = true).assertCountEquals(7)
    }

    @Test
    fun `chrome stays shown under an open sheet on the dock`() {
        val ui = CallUiController()
        var tabletop by mutableStateOf<TabletopGeometry?>(null)
        var keep = false
        var bottomVisible = false
        rule.setContent {
            CompositionLocalProvider(LocalCallTabletop provides tabletop) {
                keep = rememberKeepsChromeUnderPanels()
                bottomVisible = rememberChromeVisible(ui, ui.showBottomToolBarViewEnabled).value
            }
        }
        ui.setShowToolBarBottomViewEnable(true)
        rule.waitForIdle()
        assertFalse("portrait bar mode fades the chrome under a sheet", bottomVisible)

        tabletop = geometry(1824, 2580, 3f)
        rule.waitForIdle()
        assertTrue(keep)
        assertTrue("the dock keeps the chrome under a sheet", bottomVisible)
    }
}
