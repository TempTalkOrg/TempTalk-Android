package com.difft.android.chat.ui.messageaction

import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.ui.messageaction.ListScrollFixture.Companion.ITEM_COUNT
import com.difft.android.chat.ui.messageaction.ListScrollFixture.Companion.SCROLL_PAST_ANCHOR
import com.difft.android.chat.ui.messageaction.ListScrollFixture.Companion.idle
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertNotNull








@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MessageActionCoordinatorScrollTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule()

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val globalConfigsManager: IGlobalConfigsManager = mockk {
        every { getNewGlobalConfigs() } returns null
    }

    private var fixture: ListScrollFixture? = null

    @After
    fun tearDown() {
        fixture?.destroy()
    }

    private fun fixture() = ListScrollFixture(composeRule, globalConfigsManager).also { fixture = it }

    @Test
    fun `LS1 a dragged scroll hides the menu and the highlight follows the text`() {
        val f = fixture()
        f.showAndIdle()
        assertTrue(f.overlay.isMenuVisible)
        val textTopBefore = IntArray(2).also(f.textView::getLocationOnScreen)[1]

        f.dragUp(fromY = f.overlay.height * 0.8f, step = 100f, steps = 3)

        val textTopAfter = IntArray(2).also(f.textView::getLocationOnScreen)[1]
        assertTrue("list must have scrolled", textTopAfter < textTopBefore)
        assertFalse(f.overlay.isMenuVisible)
        assertTrue(f.coordinator.isShowing)
        assertEquals(0f, f.highlightOffsetFromText(), 0.5f)
        assertTrue(f.manager.hasSelection())
    }

    @Test
    fun `LS2 settling after a drag re-shows the menu with refreshed bounds and re-anchored rows`() {
        val f = fixture()
        f.showAndIdle()
        val bubbleBefore = f.popup.bubbleBounds

        val y = f.dragUp(fromY = f.overlay.height * 0.8f, step = 100f, steps = 3)
        f.release(y)
        f.settle()

        assertTrue(f.coordinator.isShowing)
        assertTrue(f.overlay.isMenuVisible)
        assertNotEquals(bubbleBefore, f.popup.bubbleBounds)
        assertEquals(f.expectedBubbleBounds(), f.popup.bubbleBounds)
        assertTrue(f.manager.hasSelection())
        assertNotEquals(SelectionRowRects.NONE, f.popup.selectionRows)
        assertEquals(f.manager.getVisibleHandleRowRects(), f.popup.selectionRows)
    }

    @Test
    fun `LS3 a programmatic scroll while idle does not hide the menu but re-anchors`() {
        val f = fixture()
        f.showAndIdle()
        val bubbleBefore = f.popup.bubbleBounds

        f.rv.scrollBy(0, 150)
        
        assertTrue(f.overlay.isMenuVisible)
        assertEquals(f.expectedBubbleBounds(), f.popup.bubbleBounds)
        f.settle()

        assertTrue(f.overlay.isMenuVisible)
        assertTrue(f.coordinator.isShowing)
        assertNotEquals(bubbleBefore, f.popup.bubbleBounds)
        assertEquals(0f, f.highlightOffsetFromText(), 0.5f)
        assertTrue(f.manager.hasSelection())
        assertNotEquals(SelectionRowRects.NONE, f.popup.selectionRows)
        assertEquals(f.manager.getVisibleHandleRowRects(), f.popup.selectionRows)
    }

    @Test
    fun `LS4 scrolling the message out hides everything at once and dismisses at gesture end`() {
        val f = fixture()
        f.showAndIdle()
        val overlay = f.overlay

        val y = f.dragUp(fromY = f.overlay.height * 0.95f, step = 400f, steps = 20)

        assertFalse("anchor must be detached", f.bubble.isAttachedToWindow)
        assertTrue("dismiss waits for the gesture to end", f.coordinator.isShowing)
        (0 until overlay.childCount).forEach { assertEquals(View.INVISIBLE, overlay.getChildAt(it).visibility) }

        f.send(MotionEvent.ACTION_UP, f.gestureX, y, 6_000)

        assertFalse(f.coordinator.isShowing)
        assertTrue(f.coordinator.selectionManagerForTest == null)
    }

    @Test
    fun `LS5 the message leaving without a gesture dismisses on the next frame`() {
        val f = fixture()
        f.showAndIdle()

        f.rv.scrollBy(0, SCROLL_PAST_ANCHOR)

        assertFalse(f.bubble.isAttachedToWindow)
        assertTrue(f.coordinator.isShowing)
        idle()
        assertFalse(f.coordinator.isShowing)
    }

    @Test
    fun `LS9 the message scrolled entirely under the floating header dismisses like leaving the screen`() {
        val f = fixture()
        f.showAndIdle()
        ChatContentBand.setInsets(f.rv, ChatChromeInsets(top = HEADER_PX, bottom = 0))

        
        f.rv.scrollBy(0, f.bubble.bottom - 100)
        idle()

        assertTrue(f.bubble.isAttachedToWindow)
        assertFalse(f.coordinator.isShowing)
    }

    @Test
    fun `LS9b the same scroll without floating chrome keeps the menu`() {
        val f = fixture()
        f.showAndIdle()

        f.rv.scrollBy(0, f.bubble.bottom - 100)
        idle()

        assertTrue(f.bubble.isAttachedToWindow)
        assertTrue(f.coordinator.isShowing)
    }

    @Test
    fun `LS7 the menu shows only after both the drag hide and the scroll hide clear`() {
        val f = fixture()
        f.showAndIdle()
        val dragCallback = assertNotNull(f.manager.handleDragCallback)

        dragCallback.onHandleDragStarted(isStart = false)
        val y = f.dragUp(fromY = f.overlay.height * 0.8f, step = 100f, steps = 3)
        f.release(y)
        assertFalse("drag still holds the menu hidden", f.overlay.isMenuVisible)

        dragCallback.onHandleDragEnd()
        f.settle()
        assertTrue(f.overlay.isMenuVisible)
    }

    @Test
    fun `LS8 a structural change still dismisses at once`() {
        val f = fixture()
        f.showAndIdle()

        f.adapter.notifyItemInserted(ITEM_COUNT - 1)

        assertFalse(f.coordinator.isShowing)
    }

    private companion object {
        const val HEADER_PX = 500
    }
}
