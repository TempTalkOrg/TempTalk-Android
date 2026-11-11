package com.difft.android.home

import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.glass.GlassBackdrop
import com.difft.android.base.ui.glass.LocalGlassBackdrop
import com.difft.android.base.ui.theme.DifftTheme
import org.difft.app.database.models.ContactorModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class HomeTabCapsuleTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(HomeTabCapsuleState())
    private var layoutRtl by mutableStateOf(false)

    
    private fun show(
        initial: HomeTabCapsuleState,
        onTabClick: (Int) -> Unit = {},
        onChatsDoubleTap: () -> Unit = {},
        onSearch: () -> Unit = {},
        hostWidth: androidx.compose.ui.unit.Dp? = null,
        rail: Boolean = false,
        rtl: Boolean = false,
    ) {
        state = initial
        layoutRtl = rtl
        composeTestRule.setContent {
            DifftTheme {
                val host = if (hostWidth != null) Modifier.width(hostWidth) else Modifier
                Box(host) {
                    CompositionLocalProvider(LocalLayoutDirection provides if (layoutRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        if (rail) {
                            HomeTabRail(state, onTabClick, onChatsDoubleTap, onSearch)
                        } else {
                            HomeTabCapsule(state, onTabClick, onChatsDoubleTap, onSearch)
                        }
                    }
                }
            }
        }
    }

    private fun update(next: HomeTabCapsuleState) {
        state = next
        composeTestRule.waitForIdle()
    }

    @Test
    @Config(sdk = [31, 32])
    fun `capsule and search retain ordinary glass demand on all tabs before refraction is supported`() {
        val source = object : GlassBackdrop {
            val listeners = mutableSetOf<() -> Unit>()
            var adds = 0
            var closes = 0
            override fun draw(canvas: Canvas, consumerWindowX: Float, consumerWindowY: Float) = false
            override fun addListener(listener: () -> Unit) { adds++; listeners += listener }
            override fun removeListener(listener: () -> Unit) { listeners -= listener }
            override fun invalidate() = Unit
            override fun close() { closes++ }
        }
        var visible by mutableStateOf(true)
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                CompositionLocalProvider(LocalGlassBackdrop provides source) {
                    if (visible) HomeTabCapsule(state, {}, {}, {}, Modifier.width(400.dp))
                }
            }
        }
        repeat(HomeTab.COUNT) { selected ->
            composeTestRule.runOnIdle { state = state.copy(selected = selected) }
            composeTestRule.onNodeWithTag(slotTag(selected)).assertIsSelected()
            composeTestRule.runOnIdle {
                assertEquals("capsule and search must both borrow the selected page", 2, source.listeners.size)
                assertEquals("tab changes must retain stable consumer bindings", 2, source.adds)
            }
        }
        composeTestRule.runOnIdle { visible = false }
        composeTestRule.runOnIdle {
            assertTrue(source.listeners.isEmpty())
            assertEquals(0, source.closes)
        }
    }

    @Test
    fun `exactly the selected slot reports selected and every slot keeps a 48dp touch target`() {
        show(HomeTabCapsuleState(selected = HomeTab.CONTACTS))
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CHATS)).assertIsNotSelected().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CONTACTS)).assertIsSelected().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag(slotTag(HomeTab.ME)).assertIsNotSelected().assertHeightIsAtLeast(48.dp)

        update(HomeTabCapsuleState(selected = HomeTab.ME))
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CONTACTS)).assertIsNotSelected()
        composeTestRule.onNodeWithTag(slotTag(HomeTab.ME)).assertIsSelected()
    }

    @Test
    fun `chats badge shows the count, caps at 99+, or a dot for a manual unread`() {
        show(HomeTabCapsuleState(chatsBadge = ChatsBadge.Count(7, muted = false)))
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_BADGE).assertExists().assertTextEquals("7")

        update(HomeTabCapsuleState(chatsBadge = ChatsBadge.Count(120, muted = true)))
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_BADGE).assertTextEquals("99+")

        update(HomeTabCapsuleState(chatsBadge = ChatsBadge.Dot(muted = false)))
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_BADGE_DOT).assertExists()
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_BADGE).assertDoesNotExist()

        update(HomeTabCapsuleState())
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_BADGE).assertDoesNotExist()
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_BADGE_DOT).assertDoesNotExist()
    }

    @Test
    fun `me slot falls back to the icon until a usable own contact is known, then shows the avatar`() {
        show(HomeTabCapsuleState(me = null))
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_ME_ICON).assertExists()
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_ME_AVATAR).assertDoesNotExist()

        
        update(HomeTabCapsuleState(me = ContactorModel().apply { id = "+10001" }))
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_ME_ICON).assertExists()
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_ME_AVATAR).assertDoesNotExist()

        update(HomeTabCapsuleState(me = ContactorModel().apply { id = "+10001"; name = "Alice" }))
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_ME_AVATAR).assertExists()
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_ME_ICON).assertDoesNotExist()
    }

    @Test
    fun `a tap selects its tab and two quick chats taps also fire the double-tap`() {
        val clicks = mutableListOf<Int>()
        var doubleTaps = 0
        show(HomeTabCapsuleState(), onTabClick = { clicks += it }, onChatsDoubleTap = { doubleTaps++ })

        composeTestRule.onNodeWithTag(slotTag(HomeTab.ME)).performClick()
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CHATS)).performClick()
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CHATS)).performClick()

        assertEquals(listOf(HomeTab.ME, HomeTab.CHATS, HomeTab.CHATS), clicks)
        assertEquals("second Chats tap within the timeout is the double-tap", 1, doubleTaps)
    }

    @Test
    fun `the search circle is a 62dp button that opens search`() {
        var searches = 0
        show(HomeTabCapsuleState(), onSearch = { searches++ })

        composeTestRule.onNodeWithTag(TAG_SEARCH)
            .assertWidthIsEqualTo(62.dp)
            .assertHeightIsEqualTo(62.dp)
            .performClick()

        assertEquals(1, searches)
    }

    @Test
    @Config(sdk = [33], qualifiers = "w480dp-h800dp")
    fun `a wide host gets the full 274dp capsule`() {
        
        show(HomeTabCapsuleState(), hostWidth = 400.dp)
        composeTestRule.onNodeWithTag(TAG_CAPSULE).assertWidthIsEqualTo(274.dp)
    }

    @Test
    fun `the narrowest list pane shrinks the capsule while the search circle keeps its size`() {
        
        show(HomeTabCapsuleState(), hostWidth = 280.dp)
        composeTestRule.onNodeWithTag(TAG_CAPSULE).assertWidthIsEqualTo(174.dp)
        composeTestRule.onNodeWithTag(TAG_SEARCH).assertWidthIsEqualTo(62.dp)
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CHATS)).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `touch double tap on Chats remains a click gesture rather than a pill drag`() {
        val clicks = mutableListOf<Int>()
        var doubleTaps = 0
        show(HomeTabCapsuleState(), onTabClick = { clicks += it }, onChatsDoubleTap = { doubleTaps++ })
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CHATS)).performTouchInput { doubleClick() }
        assertEquals(listOf(HomeTab.CHATS, HomeTab.CHATS), clicks)
        assertEquals(1, doubleTaps)
    }

    @Test
    fun `one shared pill moves between slots during the spring then settles on selection`() {
        show(HomeTabCapsuleState())
        composeTestRule.onAllNodesWithTag(TAG_SELECTION_PILL, useUnmergedTree = true).assertCountEquals(1)
        val start = composeTestRule.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot.center.x
        val restWidth = composeTestRule.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot.width
        val target = composeTestRule.onNodeWithTag(slotTag(HomeTab.ME)).fetchSemanticsNode().boundsInRoot.center.x
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.runOnIdle { state = state.copy(selected = HomeTab.ME) }
        
        
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag(slotTag(HomeTab.ME)).assertIsSelected()
        
        composeTestRule.mainClock.advanceTimeBy(48)
        val moving = composeTestRule.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("the pill must travel continuously between tab centers start=$start moving=$moving target=$target", moving > start && moving < target)
        val stretched = composeTestRule.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot.width
        assertTrue("spring velocity must redraw the scale layer", stretched > restWidth)
        composeTestRule.mainClock.advanceTimeBy(1000)
        val settled = composeTestRule.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot.center.x
        assertEquals(target, settled, 2f)
        assertEquals(restWidth, composeTestRule.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot.width, 2f)
        composeTestRule.mainClock.autoAdvance = true
    }

    @Test
    fun `fractional horizontal release selects nearest tab and keeps Chats badge`() {
        var doubleTaps = 0
        show(
            HomeTabCapsuleState(chatsBadge = ChatsBadge.Count(7, false)),
            onTabClick = { state = state.copy(selected = it) },
            onChatsDoubleTap = { doubleTaps++ },
        )
        composeTestRule.onNodeWithTag(TAG_CAPSULE).performTouchInput {
            swipe(Offset(width / 6f, height / 2f), Offset(width * 0.62f, height / 2f), durationMillis = 300)
        }
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CONTACTS)).assertIsSelected()
        composeTestRule.onNodeWithTag(TAG_BADGE, useUnmergedTree = true).assertTextEquals("7")
        assertEquals(0, doubleTaps)
    }

    @Test
    fun `vertical rail drag uses Y axis and the same nearest-tab pill`() {
        show(HomeTabCapsuleState(), onTabClick = { state = state.copy(selected = it) }, rail = true)
        composeTestRule.onNodeWithTag(TAG_CAPSULE).performTouchInput {
            swipe(Offset(width / 2f, height / 6f), Offset(width / 2f, height * 0.62f), durationMillis = 300)
        }
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CONTACTS)).assertIsSelected()
        val pill = composeTestRule.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot
        val selected = composeTestRule.onNodeWithTag(slotTag(HomeTab.CONTACTS)).fetchSemanticsNode().boundsInRoot
        assertEquals(selected.center.y, pill.center.y, 2f)
        composeTestRule.onAllNodesWithTag(TAG_SELECTION_PILL, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `RTL horizontal drag follows the physical finger while selecting logical tabs`() {
        show(HomeTabCapsuleState(), onTabClick = { state = state.copy(selected = it) }, rtl = true)
        composeTestRule.onNodeWithTag(TAG_CAPSULE).performTouchInput {
            swipe(Offset(width * 5f / 6f, height / 2f), Offset(width / 6f, height / 2f), durationMillis = 300)
        }
        composeTestRule.onNodeWithTag(slotTag(HomeTab.ME)).assertIsSelected()
        val pill = composeTestRule.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot
        val selected = composeTestRule.onNodeWithTag(slotTag(HomeTab.ME)).fetchSemanticsNode().boundsInRoot
        assertEquals(selected.center.x, pill.center.x, 2f)
    }

    @Test
    fun `layout direction change during drag restores controlled selection without a click`() {
        val clicks = mutableListOf<Int>()
        show(HomeTabCapsuleState(), onTabClick = { clicks += it })
        composeTestRule.onNodeWithTag(TAG_CAPSULE).performTouchInput {
            down(Offset(width / 6f, height / 2f))
            moveTo(Offset(width * 0.8f, height / 2f), delayMillis = 150)
        }
        composeTestRule.runOnIdle {
            layoutRtl = true
            state = state.copy(selected = HomeTab.CONTACTS)
        }
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CONTACTS)).assertIsSelected()
        val pill = composeTestRule.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot
        val selected = composeTestRule.onNodeWithTag(slotTag(HomeTab.CONTACTS)).fetchSemanticsNode().boundsInRoot
        assertEquals(selected.center.x, pill.center.x, 2f)
        composeTestRule.onNodeWithTag(TAG_CAPSULE).performTouchInput { cancel() }
        assertTrue(clicks.isEmpty())
        update(state.copy(selected = HomeTab.ME))
        val moved = composeTestRule.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot
        val me = composeTestRule.onNodeWithTag(slotTag(HomeTab.ME)).fetchSemanticsNode().boundsInRoot
        assertEquals(me.center.x, moved.center.x, 2f)
    }

    @Test
    fun `cancelled drag restores selection without dispatching a tab click`() {
        val clicks = mutableListOf<Int>()
        show(HomeTabCapsuleState(), onTabClick = { clicks += it })
        composeTestRule.onNodeWithTag(TAG_CAPSULE).performTouchInput {
            down(Offset(width / 6f, height / 2f))
            moveTo(Offset(width * 0.8f, height / 2f), delayMillis = 150)
            cancel()
        }
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CHATS)).assertIsSelected()
        assertTrue(clicks.isEmpty())
        val pill = composeTestRule.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot
        val selected = composeTestRule.onNodeWithTag(slotTag(HomeTab.CHATS)).fetchSemanticsNode().boundsInRoot
        assertEquals(selected.center.x, pill.center.x, 2f)
    }

}
