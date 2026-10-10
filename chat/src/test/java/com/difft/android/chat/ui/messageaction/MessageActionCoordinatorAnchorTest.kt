package com.difft.android.chat.ui.messageaction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * K1-K10 (#1294): [MessageActionCoordinator] keeps the menu anchored to the live selection rows
 * and hides it only while a handle is dragged beyond the touch slop.
 *
 * Handle gestures are dispatched straight to the handle view (rawY = y), except K7, which goes
 * through the window so removing the overlay delivers the real ACTION_CANCEL to the handle, and
 * K10, which goes through the window so the overlay's (non-split) multi-touch dispatch is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MessageActionCoordinatorAnchorTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule()

    // Per-test recomposer and frame clock (see MessageActionPopupAnchorTest).
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val globalConfigsManager: IGlobalConfigsManager = mockk {
        every { getNewGlobalConfigs() } returns null
    }

    private var controller: ActivityController<FragmentActivity>? = null

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
    }

    /** Records drag signals and delegates to the coordinator's callback. */
    private class DragSpy(private val delegate: TextSelectionManager.HandleDragCallback) :
        TextSelectionManager.HandleDragCallback {
        var started = 0
        var ends = 0
        var onEndBeforeDelegate: (() -> Unit)? = null
        override fun onHandleDrag(screenY: Float, isStart: Boolean) = delegate.onHandleDrag(screenY, isStart)
        override fun onHandleDragStarted(isStart: Boolean) {
            started++
            delegate.onHandleDragStarted(isStart)
        }
        override fun onHandleDragEnd() {
            ends++
            onEndBeforeDelegate?.invoke()
            delegate.onHandleDragEnd()
        }
    }

    private inner class Fixture {
        val activity: FragmentActivity =
            Robolectric.buildActivity(FragmentActivity::class.java).setup().also { controller = it }.get()
        val container = FrameLayout(activity)
        val bubble = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val textView = TextView(activity).apply { text = (0 until LINE_COUNT).joinToString("\n") { "Line $it" } }
        val coordinator = MessageActionCoordinator(activity, globalConfigsManager, translateSupported = true)
        val slop = ViewConfiguration.get(activity).scaledTouchSlop

        init {
            val textWidth = (TEXT_WIDTH_DP * activity.resources.displayMetrics.density).toInt()
            bubble.addView(textView, LinearLayout.LayoutParams(textWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
            container.addView(bubble, FrameLayout.LayoutParams(WRAP, WRAP).apply { topMargin = BUBBLE_TOP })
            activity.setContentView(FrameLayout(activity).apply { addView(container, FrameLayout.LayoutParams(MATCH, MATCH)) })
            shadowOf(Looper.getMainLooper()).idle()
        }

        val popup: MessageActionPopup get() = assertNotNull(coordinator.popupForTest)
        val manager: TextSelectionManager get() = coordinator.selectionManagerForTest!!
        val overlay: MessageActionOverlayLayout get() = popup.getOverlayContainer() as MessageActionOverlayLayout
        val handles: List<SelectionHandleView> get() = (0 until overlay.childCount).map(overlay::getChildAt).filterIsInstance<SelectionHandleView>()
        val endHandle: SelectionHandleView get() = handles[1]
        val lineHeight: Float get() = (textView.layout.getLineBottom(0) - textView.layout.getLineTop(0)).toFloat()

        fun show(enableTextSelection: Boolean = true) {
            val message = TextChatMessage().apply {
                id = "msg-1"
                authorId = "author-1"
                isMine = true
                message = textView.text.toString()
            }
            coordinator.show(
                message = message,
                messageView = bubble,
                textView = textView,
                mostUseEmojis = listOf("👍"),
                isForForward = false,
                isSaved = false,
                touchPoint = Point(0, 0),
                containerView = container,
                enableTextSelection = enableTextSelection,
            )
        }

        /** Runs the attach post, passes the 100ms isInitializing window, and places the menu. */
        fun showAndIdle(enableTextSelection: Boolean = true) {
            show(enableTextSelection)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            settle()
        }

        /** Three frames; state writes are delivered explicitly because a hidden menu is not drawn. */
        fun settle() {
            repeat(3) {
                Snapshot.sendApplyNotifications()
                composeRule.mainClock.advanceTimeByFrame()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                popup.getOverlayContainer()?.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
            }
        }

        fun spy(): DragSpy = DragSpy(manager.handleDragCallback!!).also { manager.handleDragCallback = it }
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /** Dispatches a multi-pointer event; [pointers] are (pointerId, x, y) in index order. */
    private fun touchPointers(view: View, action: Int, downTime: Long, vararg pointers: Triple<Int, Float, Float>) {
        val props = pointers.map { (id, _, _) ->
            MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coords = pointers.map { (_, x, y) ->
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, pointers.size, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /** K3 steps: end handle DOWN, MOVE up two lines (beyond slop), MOVE a further 10px. Returns visibility per step. */
    private fun dragEndHandleUp(f: Fixture): List<Boolean> {
        val handle = f.endHandle
        val up = -2 * f.lineHeight
        val seen = mutableListOf<Boolean>()
        touch(handle, MotionEvent.ACTION_DOWN, 0f, 0f)
        seen += f.overlay.isMenuVisible
        touch(handle, MotionEvent.ACTION_MOVE, 0f, up)
        seen += f.overlay.isMenuVisible
        touch(handle, MotionEvent.ACTION_MOVE, 0f, up - 10f)
        seen += f.overlay.isMenuVisible
        return seen
    }

    private fun releaseEndHandle(f: Fixture) {
        touch(f.endHandle, MotionEvent.ACTION_UP, 0f, -2 * f.lineHeight - 10f)
    }

    @Test
    fun `K1 attach re-anchors the menu to the handle rows`() {
        val f = Fixture()
        f.showAndIdle()

        val rows = f.popup.selectionRows
        assertEquals(f.manager.getVisibleHandleRowRects(), rows)
        assertNotNull(rows.start)
        assertNotNull(rows.end)
    }

    @Test
    fun `K2 the menu anchors to the bubble before the selection is attached`() {
        val f = Fixture()
        f.show()

        assertEquals(SelectionRowRects.NONE, f.popup.selectionRows)
        assertNull(f.coordinator.selectionManagerForTest)
    }

    @Test
    fun `K3 the menu hides once the slop is crossed, not on DOWN`() {
        val f = Fixture()
        f.showAndIdle()

        assertEquals(listOf(true, false, false), dragEndHandleUp(f))
    }

    @Test
    fun `K4 drag end writes the selection mode while hidden, then re-shows re-anchored`() {
        val f = Fixture()
        f.showAndIdle()
        val initialBounds = Rect(assertNotNull(f.overlay.menuBounds))
        val initialEnd = assertNotNull(f.popup.selectionRows.end)
        val spy = f.spy()
        var modeAtEnd: MessageActionPopup.MenuMode? = null
        var visibleAtEnd: Boolean? = null
        spy.onEndBeforeDelegate = {
            modeAtEnd = f.popup.menuMode
            visibleAtEnd = f.overlay.isMenuVisible
        }

        dragEndHandleUp(f)
        releaseEndHandle(f)

        assertEquals(MessageActionPopup.MenuMode.SELECTION, modeAtEnd)
        assertEquals(false, visibleAtEnd)
        f.settle()
        assertTrue(f.overlay.isMenuVisible)
        val bounds = assertNotNull(f.overlay.menuBounds)
        assertTrue("SELECTION menu must be narrower: $bounds vs $initialBounds", bounds.width() < initialBounds.width())
        val rows = f.popup.selectionRows
        assertEquals(f.manager.getVisibleHandleRowRects().end, rows.end)
        assertTrue(assertNotNull(rows.end).top < initialEnd.top)
    }

    @Test
    fun `K5 a handle tap keeps the menu visible and the rows unchanged`() {
        val f = Fixture()
        f.showAndIdle()
        val before = f.popup.selectionRows

        touch(f.endHandle, MotionEvent.ACTION_DOWN, 0f, 0f)
        assertTrue(f.overlay.isMenuVisible)
        touch(f.endHandle, MotionEvent.ACTION_UP, 0f, 0f)
        assertTrue(f.overlay.isMenuVisible)

        assertEquals(before, f.popup.selectionRows)
    }

    @Test
    fun `K6 Select All re-anchors to the full selection without a drag`() {
        val f = Fixture()
        f.showAndIdle()
        val initialEnd = f.popup.selectionRows.end
        dragEndHandleUp(f)
        releaseEndHandle(f)
        f.settle()
        assertEquals(MessageActionPopup.MenuMode.SELECTION, f.popup.menuMode)

        f.manager.selectAll()

        assertEquals(MessageActionPopup.MenuMode.FULL, f.popup.menuMode)
        assertEquals(f.manager.getVisibleHandleRowRects(), f.popup.selectionRows)
        assertEquals(initialEnd, f.popup.selectionRows.end)
    }

    @Test
    fun `K7 dismiss mid-drag survives the cancel and the next show starts visible`() {
        val f = Fixture()
        f.showAndIdle()
        val spy = f.spy()
        val decor = f.activity.window.decorView
        val menu = f.overlay.menuBounds
        val handle = f.handles.first { h -> menu == null || !menu.contains(centerIn(f.overlay, h)) }
        val loc = IntArray(2).also(handle::getLocationInWindow)
        val x = loc[0] + handle.width / 2f
        val y = loc[1] + handle.height / 2f

        touch(decor, MotionEvent.ACTION_DOWN, x, y)
        touch(decor, MotionEvent.ACTION_MOVE, x, y + 3 * f.slop)
        assertEquals(1, spy.started)
        assertFalse(f.overlay.isMenuVisible)

        f.coordinator.dismiss()

        assertEquals("removing the overlay must cancel the handle drag", 1, spy.ends)
        assertFalse(f.coordinator.isShowing)
        assertNull(f.coordinator.popupForTest)

        f.showAndIdle()
        assertTrue(f.coordinator.isShowing)
        assertTrue(f.overlay.isMenuVisible)
        dragEndHandleUp(f)
        assertFalse("hidden-for-drag flag must be reset for the next session", f.overlay.isMenuVisible)
    }

    @Test
    fun `K8 disabled selection keeps the bubble anchor and adds no handles`() {
        val f = Fixture()
        f.showAndIdle(enableTextSelection = false)

        assertNull(f.coordinator.selectionManagerForTest)
        assertEquals(SelectionRowRects.NONE, f.popup.selectionRows)
        assertTrue(f.handles.isEmpty())
        assertEquals(1, f.overlay.childCount)
    }

    /**
     * Rows are asserted to match the settled selection, not to be unchanged: `handleDrag` maps the
     * handle view's top (above the end line by its touch padding) to an offset, so any MOVE on the
     * end handle, even a zero-net one, settles the end one line up. That is pre-existing behaviour.
     */
    @Test
    fun `K9 sub-slop jitter never hides the menu`() {
        val f = Fixture()
        f.showAndIdle()
        val spy = f.spy()
        val handle = f.endHandle
        val jitter = (f.slop - 1).toFloat()

        touch(handle, MotionEvent.ACTION_DOWN, 0f, 0f)
        assertTrue(f.overlay.isMenuVisible)
        touch(handle, MotionEvent.ACTION_MOVE, 0f, jitter)
        assertTrue(f.overlay.isMenuVisible)
        touch(handle, MotionEvent.ACTION_MOVE, 0f, 0f)
        assertTrue(f.overlay.isMenuVisible)
        touch(handle, MotionEvent.ACTION_UP, 0f, 0f)
        assertTrue(f.overlay.isMenuVisible)

        assertEquals(0, spy.started)
        assertEquals(1, spy.ends)
        assertEquals(f.manager.getVisibleHandleRowRects(), f.popup.selectionRows)
    }

    /**
     * A second finger landing on the other handle while one handle is dragged must not start a
     * second drag: the overlay does not split motion events, so the second pointer joins the first
     * handle's gesture, which ignores it. The drag ends, and the menu re-shows, only on the first
     * finger's UP. Handle A is the one outside the menu (as in K7); B is the other handle.
     */
    @Test
    fun `K10 a second finger on the other handle neither ends the drag nor moves that handle`() {
        val f = Fixture()
        f.showAndIdle()
        val spy = f.spy()
        val decor = f.activity.window.decorView
        val menu = f.overlay.menuBounds
        val a = f.handles.first { h -> menu == null || !menu.contains(centerIn(f.overlay, h)) }
        val b = f.handles.first { it !== a }
        fun centerInWindow(v: View): Pair<Float, Float> {
            val loc = IntArray(2).also(v::getLocationInWindow)
            return loc[0] + v.width / 2f to loc[1] + v.height / 2f
        }
        val (ax, ay) = centerInWindow(a)
        val (bx, by) = centerInWindow(b)
        val bBefore = listOf(b.left, b.top, b.translationX, b.translationY)
        val ay2 = ay + 3 * f.slop
        val down = SystemClock.uptimeMillis()
        val shift = MotionEvent.ACTION_POINTER_INDEX_SHIFT

        touchPointers(decor, MotionEvent.ACTION_DOWN, down, Triple(0, ax, ay))
        touchPointers(decor, MotionEvent.ACTION_MOVE, down, Triple(0, ax, ay2))
        assertEquals(1, spy.started)
        assertFalse(f.overlay.isMenuVisible)

        touchPointers(
            decor, MotionEvent.ACTION_POINTER_DOWN or (1 shl shift), down,
            Triple(0, ax, ay2), Triple(1, bx, by),
        )
        touchPointers(decor, MotionEvent.ACTION_MOVE, down, Triple(0, ax, ay2), Triple(1, bx, by - 3 * f.slop))
        touchPointers(
            decor, MotionEvent.ACTION_POINTER_UP or (1 shl shift), down,
            Triple(0, ax, ay2), Triple(1, bx, by - 3 * f.slop),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("the second finger must not re-show the menu", f.overlay.isMenuVisible)
        assertEquals(0, spy.ends)
        assertEquals(1, spy.started)
        assertEquals(bBefore, listOf(b.left, b.top, b.translationX, b.translationY))

        touchPointers(decor, MotionEvent.ACTION_UP, down, Triple(0, ax, ay2))
        assertEquals(1, spy.ends)
        f.settle()
        assertTrue(f.overlay.isMenuVisible)
    }

    private fun centerIn(overlay: View, v: View): Point {
        val o = IntArray(2).also(overlay::getLocationInWindow)
        val l = IntArray(2).also(v::getLocationInWindow)
        return Point(l[0] - o[0] + v.width / 2, l[1] - o[1] + v.height / 2)
    }

    private fun Rect.contains(p: Point) = contains(p.x, p.y)

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
        const val BUBBLE_TOP = 200
        const val TEXT_WIDTH_DP = 300
        const val LINE_COUNT = 10
    }
}
