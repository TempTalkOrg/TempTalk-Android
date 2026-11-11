package com.difft.android.call.ui.actionbar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.service.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs


private const val TOLERANCE = 1f








@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [30], qualifiers = "w860dp-h608dp-land-xhdpi")
class CallSideRailLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    
    private val topInset = 24.dp

    private val rail = SideRail(topInset = topInset)
    private val rootClicks = mutableIntStateOf(0)
    private val endMenuOpens = mutableIntStateOf(0)
    private val minimizeClicks = mutableIntStateOf(0)

    private fun slotTag(slot: RailSlot) = if (slot == RailSlot.END) "call_btn_rail_end" else "slot_${slot.name}"

    @Composable
    private fun FakeSlot(slot: RailSlot) {
        Box(Modifier.size(CallSideRailSpec.BUTTON_DP.dp).testTag(slotTag(slot)).clickable {})
    }

    private fun slots() = ActionBarSlots(
        mic = { FakeSlot(RailSlot.MIC) },
        video = { FakeSlot(RailSlot.VIDEO) },
        speaker = { FakeSlot(RailSlot.SPEAKER) },
        invite = { error("Invite is never on the rail") },
        people = { FakeSlot(RailSlot.PEOPLE) },
        more = { FakeSlot(RailSlot.MORE) },
        emoji = { FakeSlot(RailSlot.EMOJI) },
        end = { RailGroupEndButton(onShowEndMenu = { endMenuOpens.intValue++ }) },
    )

    private fun setRail(
        order: List<RailSlot>,
        direction: LayoutDirection = LayoutDirection.Ltr,
        composed: Boolean = true,
    ) {
        composeTestRule.setContent {
            DifftTheme(darkTheme = true) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    
                    Box(Modifier.fillMaxSize().clickable { rootClicks.intValue++ }) {
                        CallSideRailOverlay(
                            rail = rail,
                            order = order,
                            slots = slots(),
                            onMinimize = { minimizeClicks.intValue++ },
                            composed = composed,
                            alpha = { 1f },
                        )
                    }
                }
            }
        }
    }

    private fun bounds(tag: String): DpRect = composeTestRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun rootBounds(): DpRect = composeTestRule.onRoot().getUnclippedBoundsInRoot()

    private fun assertDp(message: String, expected: Dp, actual: Dp) {
        assertTrue("$message: expected $expected, was $actual", abs(expected.value - actual.value) <= TOLERANCE)
    }

    @Test
    fun `controls stack top to bottom in order, End in the bottom-end corner`() {
        val order = railSlots(isGroup = true, isScreenShare = false, fitsSevenControls = false)
        setRail(order)

        val root = rootBounds()
        val rects = order.map { bounds(slotTag(it)) }
        rects.zipWithNext().forEachIndexed { i, (upper, lower) ->
            assertDp("gap after ${order[i]}", upper.bottom + CallSideRailSpec.GAP_DP.dp, lower.top)
            assertDp("column alignment at ${order[i + 1]}", upper.left, lower.left)
        }
        val end = rects.last()
        assertDp("End bottom", root.bottom - CallSideRailSpec.BOTTOM_INSET_DP.dp, end.bottom)
        assertDp("End right", root.right - CallSideRailSpec.END_INSET_DP.dp, end.right)
        assertDp("End size", CallSideRailSpec.BUTTON_DP.dp, end.right - end.left)
        assertDp("stack height", CallSideRailSpec.STACK_HEIGHT_DP.dp, end.bottom - rects.first().top)
        composeTestRule.onNodeWithTag("call_btn_rail_end").assertContentDescriptionEquals("End")
    }

    @Test
    fun `minimize sits at the top of the rail, level with the title bar, above the stack`() {
        setRail(railSlots(isGroup = true, isScreenShare = false, fitsSevenControls = false))

        val root = rootBounds()
        val minimize = bounds("call_rail_zoom_out")
        val mic = bounds(slotTag(RailSlot.MIC))
        val end = bounds(slotTag(RailSlot.END))
        assertDp("minimize top", root.top + topInset, minimize.top)
        assertDp("minimize size", CallSideRailSpec.BUTTON_DP.dp, minimize.bottom - minimize.top)
        assertDp("minimize column", end.left, minimize.left)
        assertTrue("gap to the stack", mic.top - minimize.bottom >= CallSideRailSpec.MIN_MINIMIZE_GAP_DP.dp - TOLERANCE.dp)
        
        assertDp("End bottom", root.bottom - CallSideRailSpec.BOTTOM_INSET_DP.dp, end.bottom)

        composeTestRule.onNodeWithTag("call_rail_zoom_out").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, minimizeClicks.intValue)
        assertEquals(0, rootClicks.intValue)
    }

    @Test
    fun `screen share order puts People in the fourth slot`() {
        val order = railSlots(isGroup = true, isScreenShare = true, fitsSevenControls = false)
        setRail(order)

        val people = bounds(slotTag(RailSlot.PEOPLE))
        val speaker = bounds(slotTag(RailSlot.SPEAKER))
        val more = bounds(slotTag(RailSlot.MORE))
        assertTrue(people.top > speaker.bottom && people.bottom < more.top)
    }

    @Test
    fun `seven controls fit the fold's inner screen below the minimize control`() {
        val order = railSlots(isGroup = true, isScreenShare = true, fitsSevenControls = true)
        setRail(order)

        val root = rootBounds()
        val rects = order.map { bounds(slotTag(it)) }
        rects.zipWithNext().forEachIndexed { i, (upper, lower) ->
            assertDp("gap after ${order[i]}", upper.bottom + CallSideRailSpec.GAP_DP.dp, lower.top)
        }
        assertDp("stack height", CallSideRailSpec.ROOMY_STACK_HEIGHT_DP.dp, rects.last().bottom - rects.first().top)
        assertDp("End bottom", root.bottom - CallSideRailSpec.BOTTOM_INSET_DP.dp, rects.last().bottom)
        val minimize = bounds("call_rail_zoom_out")
        assertTrue("gap to the stack", rects.first().top - minimize.bottom >= CallSideRailSpec.MIN_MINIMIZE_GAP_DP.dp - TOLERANCE.dp)
    }

    @Test
    fun `RTL mirrors the rail to the left edge without reordering`() {
        val order = railSlots(isGroup = true, isScreenShare = false, fitsSevenControls = false)
        setRail(order, direction = LayoutDirection.Rtl)

        val root = rootBounds()
        val end = bounds(slotTag(RailSlot.END))
        assertDp("End left", root.left + CallSideRailSpec.END_INSET_DP.dp, end.left)
        assertDp("End bottom", root.bottom - CallSideRailSpec.BOTTOM_INSET_DP.dp, end.bottom)
        assertTrue("Mic stays on top", bounds(slotTag(RailSlot.MIC)).bottom < end.top)
        assertDp("minimize left", root.left + CallSideRailSpec.END_INSET_DP.dp, bounds("call_rail_zoom_out").left)
    }

    @Test
    fun `empty rail area passes the tap to the root, End opens the end menu`() {
        setRail(railSlots(isGroup = true, isScreenShare = false, fitsSevenControls = false))
        val more = bounds(slotTag(RailSlot.MORE))
        val end = bounds(slotTag(RailSlot.END))
        val root = rootBounds()

        with(composeTestRule.density) {
            
            val gap = Offset(((more.left + more.right) / 2).toPx(), (more.bottom + 6.dp).toPx())
            
            val inset = Offset((root.right - 12.dp).toPx(), ((end.top + end.bottom) / 2).toPx())
            composeTestRule.onRoot().performTouchInput { click(gap) }
            composeTestRule.onRoot().performTouchInput { click(inset) }
        }
        composeTestRule.waitForIdle()
        assertEquals(2, rootClicks.intValue)
        assertEquals(0, endMenuOpens.intValue)

        composeTestRule.onNodeWithTag("call_btn_rail_end").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, endMenuOpens.intValue)
        assertEquals(2, rootClicks.intValue)
    }

    @Test
    fun `hidden rail leaves the tree`() {
        setRail(railSlots(isGroup = true, isScreenShare = false, fitsSevenControls = false), composed = false)
        composeTestRule.onNodeWithTag("call_action_rail").assertDoesNotExist()
        composeTestRule.onNodeWithTag("call_rail_zoom_out").assertDoesNotExist()
    }
}
