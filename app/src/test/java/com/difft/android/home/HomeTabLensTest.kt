package com.difft.android.home

import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.glass.GlassMode
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.common.AvatarView
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.difft.app.database.models.ContactorModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w480dp-h800dp")
class HomeTabLensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private var state by mutableStateOf(HomeTabCapsuleState(chatsBadge = ChatsBadge.Count(7, false)))
    private val clicks = mutableListOf<Int>()

    @Before
    fun liveGlass() {
        mockkObject(GlassMode)
        every { GlassMode.isLive(any()) } returns true
        
        every { GlassMode.libraryEffects } returns false
    }

    @After
    fun resetMode() {
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        unmockkObject(GlassMode)
    }

    private fun show(rail: Boolean = false, rtl: Boolean = false) {
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    val select: (Int) -> Unit = {
                        clicks += it
                        state = state.copy(selected = it)
                    }
                    Box(Modifier.width(400.dp)) {
                        if (rail) HomeTabRail(state, select, {}, {})
                        else HomeTabCapsule(state, select, {}, {})
                    }
                }
            }
        }
    }

    @Test
    fun `untouched initial capsule starts with an inset resting pill and aligned RTL geometry`() {
        show(rtl = true)
        val pill = compose.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag(TAG_CAPSULE).fetchSemanticsNode().boundsInRoot
        val chats = compose.onNodeWithTag(slotTag(HomeTab.CHATS)).fetchSemanticsNode().boundsInRoot
        val skin = compose.onNodeWithTag(TAG_CAPSULE_PANEL).fetchSemanticsNode().boundsInRoot
        assertTrue("launch must not synthesize a pressed lens", pill.height < capsule.height)
        assertEquals(chats.center.x, pill.center.x, 0.5f)
        assertEquals(capsule.width, skin.width, 0.5f)
        assertEquals(capsule.height, skin.height, 0.5f)
    }

    @Test
    fun `recording the live lens input preserves one semantic row badge and click target`() {
        show()
        compose.onAllNodesWithTag(TAG_SELECTION_PILL, useUnmergedTree = true).assertCountEquals(1)
        repeat(HomeTab.COUNT) { compose.onAllNodesWithTag(slotTag(it)).assertCountEquals(1) }
        compose.onAllNodesWithTag(TAG_BADGE, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("7").assertCountEquals(1)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab), useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithTag(TAG_ME_ICON, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag(TAG_BADGE, useUnmergedTree = true).assertTextEquals("7")
        compose.onNodeWithTag(slotTag(HomeTab.CONTACTS)).performTouchInput { click() }
        compose.onNodeWithTag(slotTag(HomeTab.CONTACTS)).assertIsSelected()
        assertEquals(listOf(HomeTab.CONTACTS), clicks)
    }

    @Test
    fun `selected copy reuses the one actual avatar view and hides its semantics`() {
        state = state.copy(me = ContactorModel().apply { id = "+10001"; name = "Nate" })
        show()
        compose.onAllNodesWithTag(TAG_ME_AVATAR, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag(TAG_BADGE, useUnmergedTree = true).assertCountEquals(1)
        compose.runOnIdle {
            assertEquals("selected recording must not load a second AndroidView avatar", 1, avatarCount(compose.activity.window.decorView))
        }
    }

    @Test
    fun `horizontal RTL panel scales uniformly and shifts with physical drag then returns`() = assertPanelResponse(rail = false, rtl = true)

    @Test
    fun `vertical panel scales uniformly and shifts along its long axis then returns`() = assertPanelResponse(rail = true, rtl = true)

    private fun assertPanelResponse(rail: Boolean, rtl: Boolean) {
        show(rail, rtl)
        val initial = compose.onNodeWithTag(TAG_CAPSULE).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(slotTag(HomeTab.CONTACTS)).performTouchInput { down(center) }
        compose.waitForIdle()
        val skin = compose.onNodeWithTag(TAG_CAPSULE_PANEL).fetchSemanticsNode().boundsInRoot
        assertTrue(skin.width > initial.width)
        assertEquals("uniform panel scale must affect both axes equally", skin.width / initial.width, skin.height / initial.height, 0.001f)
        val dragDistance = with(compose.density) { 40.dp.toPx() }
        compose.onNodeWithTag(slotTag(HomeTab.CONTACTS)).performTouchInput {
            moveBy(if (rail) Offset(0f, dragDistance) else Offset(dragDistance, 0f), delayMillis = 200)
        }
        compose.waitForIdle()
        val shifted = compose.onNodeWithTag(TAG_CAPSULE).fetchSemanticsNode().boundsInRoot
        val delta = if (rail) shifted.center.y - initial.center.y else shifted.center.x - initial.center.x
        assertTrue("RTL changes logical selection, not physical panel response", delta > 0f)
        val maximum = with(compose.density) { 4.dp.toPx() }
        assertTrue(delta <= maximum + 0.5f)
        compose.onNodeWithTag(slotTag(HomeTab.CONTACTS)).performTouchInput { cancel() }
        compose.waitForIdle()
        val returned = compose.onNodeWithTag(TAG_CAPSULE).fetchSemanticsNode().boundsInRoot
        assertEquals(initial.left, returned.left, 0.5f)
        assertEquals(initial.top, returned.top, 0.5f)
        assertTrue(clicks.isEmpty())
    }

    private fun avatarCount(view: View): Int = (if (view is AvatarView) 1 else 0) +
        (if (view is ViewGroup) (0 until view.childCount).sumOf { avatarCount(view.getChildAt(it)) } else 0)

    @Test
    fun `pressed horizontal lens grows beyond its skin without clipping the semantic row`() {
        show()
        compose.onNodeWithTag(slotTag(HomeTab.CHATS)).performTouchInput { down(center) }
        compose.waitForIdle()
        val lens = compose.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag(TAG_CAPSULE).fetchSemanticsNode().boundsInRoot
        assertTrue("lens must grow beyond the skin's height", lens.height > capsule.height)
        compose.onNodeWithTag(TAG_BADGE, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(slotTag(HomeTab.CHATS)).performTouchInput { up() }
    }

    @Test
    fun `vertical RTL lens retains its shared geometry and touch semantics`() {
        show(rail = true, rtl = true)
        compose.onNodeWithTag(slotTag(HomeTab.CONTACTS)).performTouchInput { down(center) }
        compose.waitForIdle()
        val lens = compose.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag(TAG_CAPSULE).fetchSemanticsNode().boundsInRoot
        assertTrue("upright lens must grow beyond the skin's width", lens.width > capsule.width)
        compose.onNodeWithTag(slotTag(HomeTab.CONTACTS)).performTouchInput { up() }
        compose.onNodeWithTag(slotTag(HomeTab.CONTACTS)).assertIsSelected()
        assertEquals(listOf(HomeTab.CONTACTS), clicks)
        compose.onAllNodesWithTag(TAG_BADGE, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    @Config(sdk = [32])
    fun `API32 keeps the existing small press deformation and semantic row`() = assertOrdinaryPress()

    @Test
    fun `disabled live glass keeps the existing small press deformation on API33`() {
        every { GlassMode.isLive(any()) } returns false
        assertOrdinaryPress()
    }

    private fun assertOrdinaryPress() {
        show()
        compose.onNodeWithTag(slotTag(HomeTab.CHATS)).performTouchInput { down(center) }
        compose.waitForIdle()
        val pill = compose.onNodeWithTag(TAG_SELECTION_PILL).fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag(TAG_CAPSULE).fetchSemanticsNode().boundsInRoot
        assertTrue(pill.height < capsule.height)
        compose.onNodeWithTag(slotTag(HomeTab.CHATS)).performTouchInput { up() }
        compose.onNodeWithTag(slotTag(HomeTab.CHATS)).assertIsSelected()
        compose.onAllNodesWithTag(TAG_BADGE, useUnmergedTree = true).assertCountEquals(1)
    }
}
