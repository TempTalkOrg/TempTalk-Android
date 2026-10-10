package com.difft.android.chat.ui.messageaction

import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.ui.messageaction.ListScrollFixture.Companion.ANCHOR
import com.difft.android.chat.ui.messageaction.ListScrollFixture.Companion.FILLER_HEIGHT
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * LS6, LS9-LS14 (#1294): a rebind of the anchor item. The same ViewHolder rendering the same text
 * (also after a long-text re-truncation in the bind's pre-draw) keeps the selection and re-anchors to
 * the new geometry; an item without text keeps the menu; a text change or a new ViewHolder dismisses.
 * Until the check runs, nothing reads the selection against the layout.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MessageActionCoordinatorRebindTest {

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

    private fun ListScrollFixture.assertSelectionFollows() {
        assertTrue(coordinator.isShowing)
        assertTrue(overlay.isMenuVisible)
        assertEquals(expectedBubbleBounds(), popup.bubbleBounds)
        assertTrue(manager.hasSelection())
        assertNotEquals(SelectionRowRects.NONE, popup.selectionRows)
        assertEquals(manager.getVisibleHandleRowRects(), popup.selectionRows)
        assertEquals(0f, highlightOffsetFromText(), 0.5f)
    }

    @Test
    fun `LS6 a rebind with unchanged text re-anchors to the moved bubble, another item keeps the menu`() {
        val f = fixture()
        f.showAndIdle()

        f.adapter.notifyItemChanged(ANCHOR + 2)
        idle()
        assertTrue(f.coordinator.isShowing)

        val bubbleBefore = f.popup.bubbleBounds
        val rowsBefore = f.popup.selectionRows
        // A payload rebind (receipts, reactions) of the anchor and its taller neighbour above moves the text.
        f.adapter.leadHeight = FILLER_HEIGHT + LEAD_GROWTH
        f.adapter.notifyItemRangeChanged(0, ANCHOR + 1, "receipt")
        idle()
        f.settle()

        assertNotEquals(bubbleBefore, f.popup.bubbleBounds)
        assertNotEquals(rowsBefore, f.popup.selectionRows)
        f.assertSelectionFollows()
    }

    @Test
    fun `LS9 a rebind that changes the text dismisses`() {
        val f = fixture()
        f.showAndIdle()

        f.adapter.anchorText = "Edited"
        f.adapter.notifyItemChanged(ANCHOR)
        idle()

        assertFalse(f.coordinator.isShowing)
    }

    @Test
    fun `LS10 a rebind into a new ViewHolder under a change animation dismisses within a frame`() {
        val f = fixture()
        f.showAndIdle()
        val animator = HeldChangeAnimator()
        f.rv.itemAnimator = animator

        f.adapter.notifyItemChanged(ANCHOR)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))

        // The old ViewHolder stays attached while its change animation runs, so the detach path did not fire.
        assertTrue("change animation started", animator.changes > 0)
        assertTrue("old holder still attached", f.bubble.isAttachedToWindow)
        assertFalse(f.coordinator.isShowing)
    }

    /** Starts change animations but never finishes them, so the old ViewHolder stays attached. */
    private class HeldChangeAnimator : DefaultItemAnimator() {
        var changes = 0

        override fun animateChange(
            oldHolder: RecyclerView.ViewHolder,
            newHolder: RecyclerView.ViewHolder,
            fromLeft: Int,
            fromTop: Int,
            toLeft: Int,
            toTop: Int,
        ): Boolean {
            changes++
            return false
        }
    }

    @Test
    fun `LS11 a long-text rebind re-truncated in the bind's pre-draw keeps the selection`() {
        val f = fixture()
        f.adapter.truncateToLines = TRUNCATED_LINES
        f.adapter.notifyItemChanged(ANCHOR)
        idle()
        f.showAndIdle()
        val truncated = f.textView.text.toString()
        assertTrue(truncated.endsWith("Read more"))

        f.adapter.notifyItemChanged(ANCHOR, "receipt")
        idle()
        f.settle()

        assertEquals(truncated, f.textView.text.toString())
        f.assertSelectionFollows()
    }

    @Test
    fun `LS12 a rebind of an anchor without text keeps the menu and refreshes the bounds`() {
        val f = fixture()
        f.showAndIdle(withText = false)
        val bubbleBefore = f.popup.bubbleBounds

        f.adapter.leadHeight = FILLER_HEIGHT + LEAD_GROWTH
        f.adapter.notifyItemRangeChanged(0, ANCHOR + 1, "receipt")
        idle()
        f.settle()

        assertTrue(f.coordinator.isShowing)
        assertTrue(f.overlay.isMenuVisible)
        assertNotEquals(bubbleBefore, f.popup.bubbleBounds)
        assertEquals(f.expectedBubbleBounds(), f.popup.bubbleBounds)
    }

    @Test
    fun `LS13 IDLE while a rebind check is pending waits for the check, which then settles`() {
        val f = fixture()
        f.showAndIdle()
        val rowsBefore = f.popup.selectionRows

        val y = f.dragUp(fromY = f.overlay.height * 0.8f, step = 100f, steps = 3)
        f.adapter.anchorPaddingTop = PADDING_GROWTH
        f.adapter.notifyItemChanged(ANCHOR, "receipt")
        f.lift(y)

        assertEquals(RecyclerView.SCROLL_STATE_IDLE, f.rv.scrollState)
        assertFalse("IDLE does not settle while the check is pending", f.overlay.isMenuVisible)

        idle()
        f.settle()

        assertNotEquals(rowsBefore, f.popup.selectionRows)
        f.assertSelectionFollows()
    }

    @Test
    fun `LS14 a text shorter than the selection offsets is read safely when a check is missed`() {
        val f = fixture()
        f.showAndIdle()

        // No adapter notification: the selection still holds the offsets of the long text.
        f.textView.text = "Short"
        f.rv.scrollBy(0, 50)
        f.settle()

        assertTrue(f.coordinator.isShowing)
        assertEquals("Short", f.manager.getSelectedText())
        assertEquals(f.manager.getVisibleHandleRowRects(), f.popup.selectionRows)
    }

    private companion object {
        const val LEAD_GROWTH = 200
        const val PADDING_GROWTH = 120
        const val TRUNCATED_LINES = 40
    }
}
