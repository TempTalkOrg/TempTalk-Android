package com.difft.android.chat.ui.messageaction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.fragment.app.FragmentActivity
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Z1-Z12 (#1294): [MessageActionOverlayLayout] draws the menu above the selection handles and
 * routes a DOWN inside the visible menu to the menu before any child.
 *
 * The menu stand-in is a MATCH_PARENT View whose touch listener records events and returns false
 * (a non-consuming Compose gap). Because the menu is the highest-Z child, the framework also
 * *offers* it the DOWN on the child route; "did not own the sequence" is therefore asserted as
 * "received no event other than that offered DOWN". mdpi, so px == dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34], qualifiers = "w800dp-h1400dp-mdpi")
class MessageActionOverlayLayoutTest {

    private val controllers = mutableListOf<ActivityController<FragmentActivity>>()
    private var dismissCount = 0
    private val menuEvents = mutableListOf<Int>()
    private var dragStarts = 0
    private var drags = 0
    private var dragEnds = 0

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.destroy() } }
    }

    private fun newActivity(): FragmentActivity =
        Robolectric.buildActivity(FragmentActivity::class.java).setup().also { controllers += it }.get()

    private fun recordingMenu(activity: FragmentActivity) = View(activity).apply {
        setBackgroundColor(Color.RED)
        setOnTouchListener { _, ev ->
            menuEvents += ev.actionMasked
            false
        }
    }

    private fun trackedHandle(activity: FragmentActivity) = SelectionHandleView(activity, isStart = false).apply {
        elevation = SELECTION_HANDLE_ELEVATION_PX
        onDragStart = { dragStarts++ }
        onDrag = { _, _ -> drags++ }
        onDragEnd = { dragEnds++ }
    }

    private fun at(left: Int, top: Int, width: Int = FrameLayout.LayoutParams.WRAP_CONTENT, height: Int = width) =
        FrameLayout.LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = top
        }

    private fun host(activity: FragmentActivity, overlay: MessageActionOverlayLayout) {
        activity.setContentView(overlay)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun send(target: View, action: Int, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, action, x, y, 0)
        return try {
            target.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    private fun tap(target: View, x: Float, y: Float) {
        send(target, MotionEvent.ACTION_DOWN, x, y)
        send(target, MotionEvent.ACTION_UP, x, y)
        idle()
    }

    private fun drag(target: View, x: Float, y: Float) {
        send(target, MotionEvent.ACTION_DOWN, x, y)
        send(target, MotionEvent.ACTION_MOVE, x + 30f, y + 30f)
        send(target, MotionEvent.ACTION_UP, x + 30f, y + 30f)
        idle()
    }

    /** Clears the long-press continuation guard; the warm-up tap dismisses, so counters reset. */
    private fun warmUp(overlay: MessageActionOverlayLayout) {
        tap(overlay, 790f, 10f)
        dismissCount = 0
        menuEvents.clear()
    }

    private fun View.centerX() = left + width / 2f
    private fun View.centerY() = top + height / 2f

    /** Menu + one end handle at (200, 400), menu bounds (100,380,700,900). */
    private inner class Fixture {
        val activity = newActivity()
        val menu = recordingMenu(activity)
        val overlay = MessageActionOverlayLayout(activity, menu) { dismissCount++ }
        val handle = trackedHandle(activity)

        init {
            overlay.addView(handle, at(200, 400))
            overlay.menuBounds = Rect(100, 380, 700, 900)
            host(activity, overlay)
            warmUp(overlay)
        }
    }

    private fun assertMenuDidNotOwnSequence() {
        assertTrue("menu got $menuEvents", menuEvents.all { it == MotionEvent.ACTION_DOWN })
    }

    @Test
    fun `Z1 software draw puts the menu above a Z-10 child and index order does not matter`() {
        val activity = newActivity()
        val menu = View(activity).apply { setBackgroundColor(Color.RED) }
        val overlay = MessageActionOverlayLayout(activity, menu) {}
        val blue = View(activity).apply {
            setBackgroundColor(Color.BLUE)
            elevation = SELECTION_HANDLE_ELEVATION_PX
        }
        overlay.addView(blue, at(200, 400, 100))
        host(activity, overlay)

        assertTrue(menu.z > blue.z)
        assertEquals(Color.RED, pixelAt(overlay, 250, 450))

        menu.translationZ = 0f
        assertEquals(Color.BLUE, pixelAt(overlay, 250, 450))
    }

    private fun pixelAt(view: View, x: Int, y: Int): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap.getPixel(x, y)
    }

    @Test
    fun `Z2 DOWN in the visible menu over a handle goes to the menu`() {
        val f = Fixture()
        val x = f.handle.centerX()
        val y = f.handle.centerY()
        assertTrue(f.overlay.menuBounds!!.contains(x.toInt(), y.toInt()))

        drag(f.overlay, x, y)

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), menuEvents)
        assertEquals(0, dragStarts)
        assertEquals(0, dismissCount)
    }

    @Test
    fun `Z3 a handle outside the menu still drags`() {
        val f = Fixture()
        f.overlay.menuBounds = Rect(100, 700, 700, 900)

        drag(f.overlay, f.handle.centerX(), f.handle.centerY())

        assertEquals(1, dragStarts)
        assertTrue(drags >= 1)
        assertEquals(1, dragEnds)
        assertMenuDidNotOwnSequence()
        assertEquals(0, dismissCount)
    }

    @Test
    fun `Z4 a hidden menu does not swallow a DOWN over a handle`() {
        val f = Fixture()
        f.overlay.setMenuVisible(false)

        drag(f.overlay, f.handle.centerX(), f.handle.centerY())

        assertEquals(View.INVISIBLE, f.menu.visibility)
        assertEquals(1, dragStarts)
        assertEquals(1, dragEnds)
        assertEquals(emptyList<Int>(), menuEvents)
    }

    @Test
    fun `Z5 a hidden menu does not swallow a tap on empty space`() {
        val f = Fixture()
        f.overlay.setMenuVisible(false)

        tap(f.overlay, 650f, 850f)

        assertEquals(1, dismissCount)
        assertEquals(emptyList<Int>(), menuEvents)
    }

    @Test
    fun `Z6 an outside tap dismisses on UP and both events are consumed`() {
        val f = Fixture()

        val down = send(f.overlay, MotionEvent.ACTION_DOWN, 50f, 50f)
        assertEquals(0, dismissCount)
        val up = send(f.overlay, MotionEvent.ACTION_UP, 50f, 50f)

        assertTrue(down && up)
        assertEquals(1, dismissCount)
    }

    @Test
    fun `Z7 the long-press continuation is swallowed until a new DOWN`() {
        val activity = newActivity()
        val overlay = MessageActionOverlayLayout(activity, recordingMenu(activity)) { dismissCount++ }
        host(activity, overlay)

        assertTrue(send(overlay, MotionEvent.ACTION_MOVE, 50f, 50f))
        assertTrue(send(overlay, MotionEvent.ACTION_UP, 50f, 50f))
        assertEquals(0, dismissCount)

        tap(overlay, 50f, 50f)
        assertEquals(1, dismissCount)
    }

    @Test
    fun `Z8 without menu bounds a tap in the menu area dismisses`() {
        val f = Fixture()
        f.overlay.menuBounds = null

        tap(f.overlay, 650f, 850f)

        assertMenuDidNotOwnSequence()
        assertEquals(1, dismissCount)
    }

    @Test
    fun `Z9 CANCEL on the menu route reaches the menu and does not dismiss`() {
        val f = Fixture()

        send(f.overlay, MotionEvent.ACTION_DOWN, 650f, 850f)
        send(f.overlay, MotionEvent.ACTION_CANCEL, 650f, 850f)

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), menuEvents)
        assertEquals(0, dismissCount)
    }

    @Test
    fun `Z10 handles attached by TextSelectionManager sit below the menu Z`() {
        val activity = newActivity()
        val overlay = MessageActionOverlayLayout(activity, View(activity)) {}
        val textView = TextView(activity).apply { text = "hello" }
        val root = FrameLayout(activity).apply {
            addView(textView)
            addView(overlay)
        }
        activity.setContentView(root)
        idle()

        TextSelectionManager(textView).attachToOverlay(overlay)

        val handles = (0 until overlay.childCount).map(overlay::getChildAt).filterIsInstance<SelectionHandleView>()
        assertEquals(2, handles.size)
        handles.forEach { assertEquals(SELECTION_HANDLE_ELEVATION_PX, it.elevation) }
        assertTrue(MENU_TRANSLATION_Z_PX > SELECTION_HANDLE_ELEVATION_PX)
    }

    @Test
    fun `Z11 a real ComposeView lets DOWNs through to handles and empty space`() {
        val activity = newActivity()
        var clicks = 0
        val composeView = ComposeView(activity).apply {
            setContent {
                val sizeDp = with(LocalDensity.current) { 600.toDp() to 520.toDp() }
                // Mirrors MessageActionPopupOverlay: an outer full-size Box without pointer input.
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .offset { IntOffset(100, 380) }
                            .size(sizeDp.first, sizeDp.second)
                            .clickable { clicks++ }
                    )
                }
            }
        }
        val overlay = MessageActionOverlayLayout(activity, composeView) { dismissCount++ }
        val handle = trackedHandle(activity)
        overlay.addView(handle, at(200, 1000))
        overlay.menuBounds = Rect(100, 380, 700, 900)
        host(activity, overlay)
        warmUp(overlay)

        // (a) Handle outside the menu: the ComposeView is offered the DOWN first and declines it.
        drag(overlay, handle.centerX(), handle.centerY())
        assertEquals(1, dragStarts)
        assertTrue(drags >= 1)
        assertEquals(1, dragEnds)
        assertEquals(0, dismissCount)

        // (b) Empty space outside the menu dismisses.
        tap(overlay, 650f, 1200f)
        assertEquals(1, dismissCount)

        // (c) Inside the clickable menu: handled by the menu, no dismiss.
        tap(overlay, 300f, 500f)
        assertEquals(1, clicks)
        assertEquals(1, dismissCount)
    }

    /** Z12: a handle drags only with the pointer that started it; other pointers never move or end it. */
    @Test
    fun `Z12 a handle ignores secondary pointers`() {
        val activity = newActivity()
        val handle = SelectionHandleView(activity, isStart = true)
        val deltas = mutableListOf<Pair<Float, Float>>()
        var ends = 0
        handle.onDrag = { dx, dy -> deltas += dx to dy }
        handle.onDragEnd = { ends++ }
        val down = SystemClock.uptimeMillis()
        val shift = MotionEvent.ACTION_POINTER_INDEX_SHIFT

        pointers(handle, MotionEvent.ACTION_DOWN, down, Triple(0, 10f, 10f))
        pointers(handle, MotionEvent.ACTION_POINTER_DOWN or (1 shl shift), down, Triple(0, 10f, 10f), Triple(1, 50f, 50f))
        // Only the secondary pointer moves: no drag distance is reported.
        pointers(handle, MotionEvent.ACTION_MOVE, down, Triple(0, 10f, 10f), Triple(1, 90f, 120f))
        assertTrue("secondary move must not drag: $deltas", deltas.all { it == 0f to 0f })
        pointers(handle, MotionEvent.ACTION_POINTER_UP or (1 shl shift), down, Triple(0, 10f, 10f), Triple(1, 90f, 120f))
        assertEquals("a non-primary POINTER_UP must not end the drag", 0, ends)

        pointers(handle, MotionEvent.ACTION_MOVE, down, Triple(0, 15f, 17f))
        assertEquals(5f to 7f, deltas.last())

        // The primary lifts first: the drag ends, and the remaining pointer's moves do not drag.
        pointers(handle, MotionEvent.ACTION_POINTER_DOWN or (1 shl shift), down, Triple(0, 15f, 17f), Triple(1, 50f, 50f))
        pointers(handle, MotionEvent.ACTION_POINTER_UP, down, Triple(0, 15f, 17f), Triple(1, 50f, 50f))
        assertEquals(1, ends)
        val dragsBefore = deltas.size
        pointers(handle, MotionEvent.ACTION_MOVE, down, Triple(1, 200f, 200f))
        assertEquals(dragsBefore, deltas.size)
        pointers(handle, MotionEvent.ACTION_UP, down, Triple(1, 200f, 200f))
        assertEquals(1, ends)
    }

    /** Dispatches a multi-pointer event; [ps] are (pointerId, x, y) in index order. */
    private fun pointers(target: View, action: Int, downTime: Long, vararg ps: Triple<Int, Float, Float>) {
        val props = ps.map { (id, _, _) ->
            MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coords = ps.map { (_, x, y) ->
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, ps.size, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            target.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }
}
