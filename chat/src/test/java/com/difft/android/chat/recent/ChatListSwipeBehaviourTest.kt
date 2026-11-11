package com.difft.android.chat.recent

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import com.difft.android.chat.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
class ChatListSwipeBehaviourTest {

    private lateinit var row: ChatListSwipeLayout
    private lateinit var foreground: View
    private var fullSwipes = 0
    private var time = 0L

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(activity, com.difft.android.base.R.style.Theme_Chative)
        row = LayoutInflater.from(context).inflate(R.layout.chat_fragment_recent_chat_list_item, null) as ChatListSwipeLayout
        row.onFullSwipeStart = { fullSwipes++ }
        
        activity.setContentView(FrameLayout(context).apply { addView(row, FrameLayout.LayoutParams(WIDTH, HEIGHT)) })
        shadowOf(Looper.getMainLooper()).idle()
        row.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
        row.layout(0, 0, WIDTH, HEIGHT)
        foreground = row.findViewById(R.id.swipe_foreground)
        
        foreground.setOnClickListener {}
        time = SystemClock.uptimeMillis()
    }

    private fun event(action: Int, x: Float): MotionEvent = MotionEvent.obtain(time, time, action, x, HEIGHT / 2f, 0)

    
    private fun drag(fromX: Float, toX: Float, steps: Int, stepMs: Long, release: Boolean = true) {
        val down = event(MotionEvent.ACTION_DOWN, fromX)
        row.dispatchTouchEvent(down)
        for (i in 1..steps) {
            time += stepMs
            row.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / steps))
        }
        if (release) {
            time += stepMs
            row.dispatchTouchEvent(event(MotionEvent.ACTION_UP, toX))
        }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

    private val endWidth get() = row.findViewById<View>(R.id.swipe_end_actions).measuredWidth.toFloat()

    @Test
    fun `past its actions the row follows the finger at a fraction`() {
        drag(380f, 380f - endWidth - 100f, steps = 10, stepMs = 16, release = false)
        val reveal = -foreground.translationX
        assertTrue("reveal $reveal stays near the actions' $endWidth", reveal > endWidth && reveal < endWidth + 30f)
    }

    @Test
    fun `a slow release short of half the actions springs back`() {
        drag(380f, 380f - endWidth * 0.4f, steps = 20, stepMs = 40)
        settle()
        assertEquals(0f, foreground.translationX, 0.5f)
    }

    @Test
    fun `a quick flick short of half the actions opens them`() {
        drag(380f, 380f - endWidth * 0.4f, steps = 3, stepMs = 8)
        settle()
        assertEquals(-endWidth, foreground.translationX, 0.5f)
    }

    @Test
    fun `the outermost button grows in first`() {
        val delete = row.findViewById<View>(R.id.action_delete)
        val pin = row.findViewById<View>(R.id.action_pin)
        drag(380f, 380f - 40f, steps = 5, stepMs = 16, release = false)
        assertTrue("delete is growing in", delete.scaleX > 0f)
        assertEquals("pin is still hidden", 0f, pin.scaleX, 0.001f)
    }

    @Test
    fun `a full swipe towards read runs it and closes the row at once`() {
        drag(20f, 20f + WIDTH * 0.7f, steps = 10, stepMs = 16)
        assertEquals(1, fullSwipes)
        assertEquals(0f, foreground.translationX, 0.001f)
    }

    private fun tap(x: Float, y: Float) {
        row.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        time += 50
        row.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_UP, x, y, 0))
        
        shadowOf(Looper.getMainLooper()).idle()
    }

    
    private fun openEndActions(): Float {
        drag(380f, 380f - endWidth * 0.4f, steps = 3, stepMs = 8)
        settle()
        assertEquals(-endWidth, foreground.translationX, 0.5f)
        val group = row.findViewById<View>(R.id.swipe_end_actions)
        val delete = row.findViewById<View>(R.id.action_delete)
        return group.left + (delete.left + delete.right) / 2f
    }

    @Test
    fun `a tap in the revealed strip beside a capsule closes the row`() {
        val x = openEndActions()
        val delete = row.findViewById<View>(R.id.action_delete)
        assertTrue("the capsule leaves room above it", delete.top > 2)
        tap(x, 1f)
        settle()
        assertEquals(0f, foreground.translationX, 0.5f)
    }

    @Test
    fun `a drag that starts beside a capsule moves the row`() {
        val x = openEndActions()
        row.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, 1f, 0))
        for (i in 1..5) {
            time += 16
            row.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_MOVE, x + i * 30f, 1f, 0))
        }
        assertTrue("the row follows the finger back", foreground.translationX > -endWidth + 50f)
    }

    @Test
    fun `a tap on a button still under the opening row closes it without a click`() {
        var rowClicks = 0
        var pins = 0
        foreground.setOnClickListener { rowClicks++ }
        val pin = row.findViewById<View>(R.id.action_pin)
        pin.setOnClickListener { pins++ }
        drag(380f, 380f - endWidth * 0.4f, steps = 3, stepMs = 8)
        val group = row.findViewById<View>(R.id.swipe_end_actions)
        val x = group.left + (pin.left + pin.right) / 2f
        assertTrue("the pin is still under the row", x < WIDTH + foreground.translationX)
        tap(x, HEIGHT / 2f)
        settle()
        assertEquals(0, rowClicks)
        assertEquals(0, pins)
        assertEquals(0f, foreground.translationX, 0.5f)
    }

    @Test
    fun `a tap on a revealed capsule reaches the button`() {
        val x = openEndActions()
        var deletes = 0
        row.findViewById<View>(R.id.action_delete).setOnClickListener { deletes++ }
        tap(x, HEIGHT / 2f)
        assertEquals(1, deletes)
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 80
    }
}
