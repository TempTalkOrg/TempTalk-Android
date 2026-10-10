package com.difft.android.chat.ui.messageaction

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import java.time.Duration

/**
 * LR1-LR10 (#1294): with a [MessageActionOverlayLayout.recyclerView], a gesture outside the menu and
 * handles scrolls the list through `onTouchEvent` copies (the DOWN also through
 * `onInterceptTouchEvent`), offset into list coordinates; only a real tap dismisses.
 *
 * The list sits 100px below the overlay origin, so a forwarded copy is the overlay event shifted
 * up by 100. mdpi, so px == dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [34], qualifiers = "w800dp-h1400dp-mdpi")
class MessageActionOverlayListRouteTest {

    private var controller: ActivityController<FragmentActivity>? = null
    private var dismissCount = 0
    private var gestureEnds = 0

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
    }

    private data class Rec(val via: String, val action: Int, val x: Float, val y: Float)

    /** Records how each event reached the list; real scrolling still happens through super. */
    private class RecordingRecyclerView(context: Context) : RecyclerView(context) {
        val events = mutableListOf<Rec>()
        var stopScrollCalls = 0

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            events += Rec("intercept", e.actionMasked, e.x, e.y)
            return super.onInterceptTouchEvent(e)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            events += Rec("touch", e.actionMasked, e.x, e.y)
            return super.onTouchEvent(e)
        }

        override fun stopScroll() {
            stopScrollCalls++
            super.stopScroll()
        }
    }

    private class FillerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 50
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, ITEM_HEIGHT)
            }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private inner class Fixture(withList: Boolean = true) {
        val activity: FragmentActivity =
            Robolectric.buildActivity(FragmentActivity::class.java).setup().also { controller = it }.get()
        val rv = RecordingRecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = FillerAdapter()
        }
        val menu = View(activity)
        val overlay = MessageActionOverlayLayout(activity, menu) { dismissCount++ }.apply {
            if (withList) recyclerView = rv
            onGestureEnd = { gestureEnds++ }
        }
        val handle = SelectionHandleView(activity, isStart = false)

        init {
            overlay.addView(handle, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 600
                topMargin = 600
            })
            activity.setContentView(FrameLayout(activity).apply {
                addView(rv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LIST_HEIGHT).apply { topMargin = LIST_TOP })
                addView(this@Fixture.overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            })
            idle()
            // Clear the long-press continuation guard with a gesture that reaches nothing.
            send(MotionEvent.ACTION_DOWN, 10f, 5f)
            send(MotionEvent.ACTION_CANCEL, 10f, 5f)
            rv.events.clear()
            dismissCount = 0
            gestureEnds = 0
        }

        private var downTime = 0L

        fun send(action: Int, x: Float, y: Float, timeOffsetMs: Long = 0) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            dispatch(MotionEvent.obtain(downTime, downTime + timeOffsetMs, action, x, y, 0))
        }

        /** Pointer 0 at (300, 500) and pointer 1 at (500, 500), both still. */
        fun sendTwoPointers(actionMasked: Int, actionIndex: Int, timeOffsetMs: Long) {
            val action = actionMasked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            dispatch(obtain(action, timeOffsetMs, intArrayOf(0, 1), floatArrayOf(300f, 500f)))
        }

        /** The final UP of pointer 1 after pointer 0 has lifted. */
        fun sendSecondPointerUp(timeOffsetMs: Long) =
            dispatch(obtain(MotionEvent.ACTION_UP, timeOffsetMs, intArrayOf(1), floatArrayOf(500f)))

        private fun obtain(action: Int, timeOffsetMs: Long, ids: IntArray, xs: FloatArray): MotionEvent {
            val props = ids.map { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = xs.map { x -> MotionEvent.PointerCoords().apply { this.x = x; y = 500f; pressure = 1f; size = 1f } }
            return MotionEvent.obtain(
                downTime, downTime + timeOffsetMs, action, ids.size, props.toTypedArray(), coords.toTypedArray(),
                0, 0, 1f, 1f, 0, 0, 0, 0,
            )
        }

        private fun dispatch(ev: MotionEvent) {
            try {
                overlay.dispatchTouchEvent(ev)
            } finally {
                ev.recycle()
            }
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    @Test
    fun `LR1 DOWN goes through intercept then touch with the offset, later events through touch only`() {
        val f = Fixture()

        f.send(MotionEvent.ACTION_DOWN, 300f, 500f)
        f.send(MotionEvent.ACTION_MOVE, 300f, 450f, 50)
        f.send(MotionEvent.ACTION_UP, 300f, 400f, 100)

        assertEquals(
            listOf(
                Rec("intercept", MotionEvent.ACTION_DOWN, 300f, 400f),
                Rec("touch", MotionEvent.ACTION_DOWN, 300f, 400f),
                Rec("touch", MotionEvent.ACTION_MOVE, 300f, 350f),
                Rec("touch", MotionEvent.ACTION_UP, 300f, 300f),
            ),
            f.rv.events,
        )
        assertEquals(1, gestureEnds)
    }

    @Test
    fun `LR2 a tap within slop without a scroll dismisses on UP`() {
        val f = Fixture()

        f.send(MotionEvent.ACTION_DOWN, 300f, 500f)
        f.send(MotionEvent.ACTION_MOVE, 302f, 501f, 30)
        assertEquals(0, dismissCount)
        f.send(MotionEvent.ACTION_UP, 302f, 501f, 60)

        assertEquals(1, dismissCount)
    }

    @Test
    fun `LR3 a drag beyond slop scrolls the list and does not dismiss`() {
        val f = Fixture()

        f.send(MotionEvent.ACTION_DOWN, 300f, 900f)
        f.send(MotionEvent.ACTION_MOVE, 300f, 700f, 50)
        f.send(MotionEvent.ACTION_MOVE, 300f, 500f, 100)
        assertTrue("list scrolled", f.rv.computeVerticalScrollOffset() > 0)
        f.send(MotionEvent.ACTION_UP, 300f, 500f, 1000)
        idle()

        assertEquals(0, dismissCount)
    }

    @Test
    fun `LR4 a tap during which the list scrolled does not dismiss`() {
        val f = Fixture()

        f.send(MotionEvent.ACTION_DOWN, 300f, 500f)
        f.rv.scrollBy(0, 40)
        f.send(MotionEvent.ACTION_UP, 300f, 500f, 60)

        assertEquals(0, dismissCount)
    }

    @Test
    fun `LR5 a tap that only stops a running fling does not dismiss`() {
        val f = Fixture()
        f.rv.fling(0, 8000)
        assertTrue(f.rv.scrollState != RecyclerView.SCROLL_STATE_IDLE)

        f.send(MotionEvent.ACTION_DOWN, 300f, 500f)
        f.send(MotionEvent.ACTION_UP, 300f, 500f, 60)

        assertEquals(0, dismissCount)
        assertEquals(RecyclerView.SCROLL_STATE_IDLE, f.rv.scrollState)
    }

    @Test
    fun `LR6 a DOWN outside the list forwards nothing but a tap there still dismisses`() {
        val f = Fixture()

        f.send(MotionEvent.ACTION_DOWN, 300f, 50f)
        f.send(MotionEvent.ACTION_UP, 300f, 50f, 60)

        assertEquals(emptyList<Rec>(), f.rv.events)
        assertEquals(1, dismissCount)
    }

    @Test
    fun `LR7 a handle DOWN stops the list scroll and the list sees nothing`() {
        val f = Fixture()
        val x = f.handle.left + f.handle.width / 2f
        val y = f.handle.top + f.handle.height / 2f
        // RecyclerView calls stopScroll() itself during setup.
        f.rv.stopScrollCalls = 0

        f.send(MotionEvent.ACTION_DOWN, x, y)
        f.send(MotionEvent.ACTION_UP, x, y, 60)

        assertEquals(1, f.rv.stopScrollCalls)
        assertEquals(emptyList<Rec>(), f.rv.events)
        assertEquals(0, dismissCount)
        assertEquals(1, gestureEnds)
    }

    @Test
    fun `LR8 CANCEL is forwarded, never dismisses, and ends the gesture`() {
        val f = Fixture()

        f.send(MotionEvent.ACTION_DOWN, 300f, 500f)
        assertTrue(f.overlay.isGestureActive)
        f.send(MotionEvent.ACTION_CANCEL, 300f, 500f, 30)

        assertEquals(MotionEvent.ACTION_CANCEL, f.rv.events.last().action)
        assertEquals(0, dismissCount)
        assertEquals(1, gestureEnds)
        assertTrue(!f.overlay.isGestureActive)
    }

    @Test
    fun `LR9 without a list a drag still dismisses on UP`() {
        val f = Fixture(withList = false)

        f.send(MotionEvent.ACTION_DOWN, 300f, 900f)
        f.send(MotionEvent.ACTION_MOVE, 300f, 500f, 50)
        f.send(MotionEvent.ACTION_UP, 300f, 500f, 100)

        assertEquals(emptyList<Rec>(), f.rv.events)
        assertEquals(1, dismissCount)
    }

    @Test
    fun `LR10 two fingers where the first lifts first do not dismiss on the last UP`() {
        val f = Fixture()

        f.send(MotionEvent.ACTION_DOWN, 300f, 500f)
        f.sendTwoPointers(MotionEvent.ACTION_POINTER_DOWN, actionIndex = 1, 30)
        f.sendTwoPointers(MotionEvent.ACTION_POINTER_UP, actionIndex = 0, 60)
        f.sendSecondPointerUp(90)

        assertEquals(0, dismissCount)
        assertTrue(!f.overlay.isGestureActive)
    }

    private companion object {
        const val LIST_TOP = 100
        const val LIST_HEIGHT = 1200
        const val ITEM_HEIGHT = 200
    }
}
