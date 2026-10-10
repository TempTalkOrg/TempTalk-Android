package com.difft.android.chat.ui.messageaction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.base.utils.dp
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.test.assertNotNull

/**
 * U1-U13 (#1294): the real [MessageActionPopup] with Compose content, placed by the positioner and
 * hosted in [MessageActionOverlayLayout].
 *
 * `settle()` advances one frame and draws the overlay, three times (recompose, measure, display).
 * The overlay (not the ComposeView) is drawn to mirror production. That a hidden menu is not
 * re-measured is NOT pinned by these tests (see U10); it is left to the on-device check.
 * xxhdpi (3 px/dp), so the 600px-wide anchor fits.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MessageActionPopupAnchorTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule()

    // Installs a per-test recomposer and frame clock; without it Compose's shared UI dispatcher
    // stays bound to the first test's Choreographer and later tests never recompose.
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val globalConfigsManager: IGlobalConfigsManager = mockk {
        every { getNewGlobalConfigs() } returns null
    }

    private val noopCallbacks = object : MessageActionPopup.Callbacks {
        override fun onReactionSelected(emoji: String, isRemove: Boolean) {}
        override fun onMoreEmojiClick() {}
        override fun onActionSelected(action: MessageAction.Type) {}
        override fun onDismiss() {}
    }

    private var controller: ActivityController<FragmentActivity>? = null

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
    }

    private inner class Fixture(
        containerParams: FrameLayout.LayoutParams = FrameLayout.LayoutParams(MATCH, MATCH),
        rtl: Boolean = false,
    ) {
        val activity: FragmentActivity =
            Robolectric.buildActivity(FragmentActivity::class.java).setup().also { controller = it }.get()
        val container = FrameLayout(activity)
        val anchorView = View(activity)
        val popup = MessageActionPopup(activity)
        val root: ViewGroup = activity.findViewById(android.R.id.content)
        // Same `Int.dp` conversion the popup uses: it reads the first Robolectric application's
        // density in the JVM, so literal xxhdpi px would break after an mdpi test class.
        val gap = 2.dp
        val pad = 8.dp

        init {
            activity.setContentView(FrameLayout(activity).apply {
                addView(container, containerParams)
                addView(anchorView, FrameLayout.LayoutParams(100, 100))
            })
            if (rtl) root.layoutDirection = View.LAYOUT_DIRECTION_RTL
            shadowOf(Looper.getMainLooper()).idle()
        }

        /** Window-space rect of the root, for converting root-relative inputs. */
        val rootWindow: Rect get() = Rect().also { root.getGlobalVisibleRect(it) }

        val contentRel: Rect
            get() = Rect().also { container.getGlobalVisibleRect(it) }.apply { offset(-rootWindow.left, -rootWindow.top) }

        /** Default bubble: full width minus a [BUBBLE_INSET] on each side, so its edges sit inside the padding. */
        val defaultBubble: Rect get() = Rect(BUBBLE_INSET, 200, contentRel.right - BUBBLE_INSET, 300)

        /** Shows the popup with a bubble at [bubbleRel] (root-relative). */
        fun show(bubbleRel: Rect = defaultBubble, outgoing: Boolean = true) {
            val window = Rect(bubbleRel).apply { offset(rootWindow.left, rootWindow.top) }
            val message = TextChatMessage().apply {
                id = "msg-1"
                authorId = "author-1"
                isMine = outgoing
                message = "hello"
            }
            val config = MessageActionConfigBuilder(globalConfigsManager, translateSupported = true).build(
                message = message,
                mostUseEmojis = listOf("👍"),
                isForForward = false,
                isSaved = false,
                anchorBounds = window,
            )
            popup.show(anchorView = anchorView, config = config, containerView = container, callbacks = noopCallbacks)
            settle()
        }

        val overlay: MessageActionOverlayLayout
            get() = popup.getOverlayContainer() as MessageActionOverlayLayout

        val menuBounds: Rect? get() = overlay.menuBounds

        fun settle() = repeat(3) { frame() }

        /**
         * One frame: deliver state writes, recompose, then draw the overlay (measure + layout +
         * positioned callbacks). In this Robolectric harness the notifications are sent explicitly:
         * drawing a ComposeView sends them, a hidden one is not drawn, and the global handler path
         * did not reliably deliver them across tests.
         */
        fun frame() {
            Snapshot.sendApplyNotifications()
            composeRule.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            popup.getOverlayContainer()?.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
        }
    }

    @Test
    fun `U1 the menu ComposeView sits above the handle elevation`() {
        val f = Fixture()
        f.show()

        val child = f.overlay.getChildAt(0)
        assertTrue(child is ComposeView)
        assertEquals(MENU_TRANSLATION_Z_PX, child.translationZ)
    }

    @Test
    fun `U2 initially anchors below the bubble aligned with its outgoing edge`() {
        val f = Fixture()
        f.show()

        assertEquals(SelectionRowRects.NONE, f.popup.selectionRows)
        val bounds = assertNotNull(f.menuBounds)
        assertEquals(300 + f.gap, bounds.top)
        assertEquals(f.defaultBubble.right, bounds.right)
    }

    @Test
    fun `U3 updated selection rows re-place the menu`() {
        val f = Fixture()
        f.show()
        val rows = SelectionRowRects(null, Rect(0, 600, 600, 650))

        f.popup.updateSelectionRows(rows)
        f.settle()

        assertEquals(rows, f.popup.selectionRows)
        assertEquals(650 + f.gap, assertNotNull(f.menuBounds).top)
    }

    @Test
    fun `U4 hide and show keeps the composition and bounds`() {
        val f = Fixture()
        f.show()
        val before = Rect(assertNotNull(f.menuBounds))
        val menu = f.overlay.getChildAt(0)

        f.popup.setMenuVisible(false)
        f.settle()
        assertEquals(View.INVISIBLE, menu.visibility)

        f.popup.setMenuVisible(true)
        f.settle()
        assertEquals(View.VISIBLE, menu.visibility)
        assertEquals(before, f.menuBounds)
    }

    @Test
    fun `U5 calls after dismiss are no-ops`() {
        val f = Fixture()
        f.show()

        f.popup.dismiss()
        f.popup.updateSelectionRows(SelectionRowRects(null, Rect(0, 600, 600, 650)))
        f.popup.setMenuVisible(false)

        assertNull(f.popup.getOverlayContainer())
        assertFalse(f.popup.isShowing)
        assertEquals(SelectionRowRects.NONE, f.popup.selectionRows)
    }

    @Test
    fun `U6 SELECTION mode re-measures narrower and keeps the right edge`() {
        val f = Fixture()
        f.show()
        val full = Rect(assertNotNull(f.menuBounds))

        f.popup.showSelectionMenu()
        f.settle()

        val selection = assertNotNull(f.menuBounds)
        assertTrue("selection=$selection full=$full", selection.width() < full.width())
        assertEquals(full.right, selection.right)
        assertEquals(300 + f.gap, selection.top)
    }

    @Test
    fun `U7 overlay-local equals root-relative`() {
        val f = Fixture()
        f.show()

        val overlayLoc = IntArray(2).also(f.overlay::getLocationInWindow)
        val rootLoc = IntArray(2).also(f.root::getLocationInWindow)
        assertEquals(rootLoc.toList(), overlayLoc.toList())
        assertEquals(0, f.root.paddingLeft)
        assertEquals(0, f.root.paddingTop)
    }

    @Test
    fun `U8 RTL reports the laid-out bounds and a tap inside them keeps the menu`() {
        val f = Fixture(rtl = true)
        f.show(outgoing = false)

        val bounds = assertNotNull(f.menuBounds)
        // Modifier.offset mirrors under RTL: x = bubble.left is drawn that far from the right edge.
        assertEquals(f.contentRel.right - f.defaultBubble.left, bounds.right)

        // Arrow strip corner: inside the menu, not on an action.
        tap(f.overlay, bounds.left + 3f, bounds.top + 3f)
        assertTrue(f.popup.isShowing)
    }

    @Test
    fun `U9 offset shortened container bounds the menu`() {
        val params = FrameLayout.LayoutParams(700, 900).apply {
            leftMargin = 300
            topMargin = 150
        }
        val f = Fixture(containerParams = params)
        val content = f.contentRel
        assertEquals(Rect(300, 150, 1000, 1050), content)

        f.show(bubbleRel = Rect(320, 200, 900, 300))

        val bounds = assertNotNull(f.menuBounds)
        // maxPanelWidth fills the 700px container minus both paddings, so the bubble alignment
        // (900 - width) is clamped to the left bound and the menu spans the whole band.
        assertEquals("bounds=$bounds", 300 + f.pad, bounds.left)
        assertEquals("bounds=$bounds", 1000 - f.pad, bounds.right)
        assertTrue("bounds=$bounds", bounds.top >= 150 + f.pad)
        assertTrue("bounds=$bounds", bounds.bottom <= 1050 - f.pad)
    }

    /**
     * #1294: the drag hide must never let a stale placement flash: a hidden menu is never shown at
     * an old position, and the menu re-shown after a FULL->SELECTION switch is placed with its new
     * size.
     *
     * The "INVISIBLE ComposeView is not re-measured" half cannot be pinned here: the
     * compose test rule's frame clock calls `measureAndLayoutForTest()` on every registered root
     * each frame, regardless of visibility (ui-test 1.11.2 `ComposeUiTest.android.kt`
     * `onPerformTraversals`). The design does not depend on it; what it depends on is pinned:
     * a hidden menu is not drawn, and the re-shown menu has the SELECTION bounds.
     */
    @Test
    fun `U10 a hidden menu is not drawn and is re-placed with its new size when shown`() {
        val f = Fixture()
        f.show()
        val full = Rect(assertNotNull(f.menuBounds))
        assertNotEquals(0, Color.alpha(pixelAt(f.overlay, full.centerX(), full.centerY())))

        f.popup.setMenuVisible(false)
        f.popup.showSelectionMenu()
        f.settle()
        assertEquals(0, Color.alpha(pixelAt(f.overlay, full.centerX(), full.centerY())))

        f.popup.setMenuVisible(true)
        f.settle()
        val shown = assertNotNull(f.menuBounds)
        assertTrue("shown=$shown full=$full", shown.width() < full.width())
        assertEquals(full.right, shown.right)
        assertNotEquals(0, Color.alpha(pixelAt(f.overlay, shown.centerX(), shown.centerY())))
    }

    /**
     * A show issued together with new rows must not flip the View VISIBLE before the composition
     * carrying those rows is applied: that frame would draw the menu at the stale placement.
     */
    @Test
    fun `U11 a show waits for the composition that carries the new rows`() {
        val f = Fixture()
        f.show()
        val menu = f.overlay.getChildAt(0)
        val rows = SelectionRowRects(null, Rect(0, 600, 600, 650))

        f.popup.setMenuVisible(false)
        f.settle()
        f.popup.updateSelectionRows(rows)
        f.popup.setMenuVisible(true)
        assertEquals("not visible before the recomposition", View.INVISIBLE, menu.visibility)

        f.frame()
        assertEquals(View.VISIBLE, menu.visibility)
        assertEquals("first visible frame is at the new placement", 650 + f.gap, assertNotNull(f.menuBounds).top)
    }

    @Test
    fun `U12 a hide cancels a pending show`() {
        val f = Fixture()
        f.show()
        val menu = f.overlay.getChildAt(0)

        f.popup.setMenuVisible(false)
        f.settle()
        f.popup.setMenuVisible(true)
        f.popup.setMenuVisible(false)
        f.settle()
        assertEquals(View.INVISIBLE, menu.visibility)

        f.popup.setMenuVisible(true)
        f.settle()
        assertEquals(View.VISIBLE, menu.visibility)
    }

    /** A deferred dismiss hides everything first; the pending show from an earlier settle must not undo it. */
    @Test
    fun `U13 hideAll cancels a pending show`() {
        val f = Fixture()
        f.show()
        val menu = f.overlay.getChildAt(0)

        f.popup.setMenuVisible(false)
        f.settle()
        f.popup.setMenuVisible(true)
        f.popup.hideAll()
        f.settle()

        assertEquals(View.INVISIBLE, menu.visibility)
    }

    private fun pixelAt(view: View, x: Int, y: Int): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap.getPixel(x, y)
    }

    private fun tap(target: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val ev = MotionEvent.obtain(now, now, action, x, y, 0)
            try {
                target.dispatchTouchEvent(ev)
            } finally {
                ev.recycle()
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT

        /** Larger than the 8dp edge padding, so bubble alignment is distinguishable from the clamp. */
        val BUBBLE_INSET: Int get() = 40.dp
    }
}
