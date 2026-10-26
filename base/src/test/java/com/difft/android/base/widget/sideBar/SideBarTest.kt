package com.difft.android.base.widget.sideBar

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SideBarTest {

    private val letters = ('A'..'Z').map { it.toString() } + "#"

    private fun layOut(heightPx: Int): Pair<SideBar, MutableList<String>> {
        val selected = mutableListOf<String>()
        val sideBar = SideBar(ApplicationProvider.getApplicationContext()).apply {
            reset(letters)
            setOnTouchingLetterChangedListener(object : SideBar.OnTouchingLetterChangedListener {
                override fun onTouchingLetterChanged(s: String) {
                    selected += s
                }
            })
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST)
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
        return sideBar to selected
    }

    private fun SideBar.touch(action: Int, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, WIDTH / 2f, y, 0)
        onTouchEvent(event)
        event.recycle()
    }

    @Test
    fun `short rail condenses and tapping its ends selects the end letters`() {
        val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        // Room for exactly three 12dp slots: A, dot, #.
        val (sideBar, selected) = layOut((36 * density).toInt())

        sideBar.touch(MotionEvent.ACTION_DOWN, 2f)
        sideBar.touch(MotionEvent.ACTION_UP, 2f)
        sideBar.touch(MotionEvent.ACTION_DOWN, sideBar.height - 2f)
        sideBar.touch(MotionEvent.ACTION_CANCEL, sideBar.height - 2f)

        assertEquals(listOf("A", "#"), selected)
    }

    @Test
    fun `touch after reset to fewer letters but before relayout stays in range`() {
        val (sideBar, selected) = layOut(10_000)

        sideBar.reset(listOf("A", "B", "C"))
        sideBar.touch(MotionEvent.ACTION_DOWN, sideBar.height - 2f)

        assertEquals(listOf("C"), selected)
    }

    @Test
    fun `rail too short for three slots ignores touches`() {
        val (sideBar, selected) = layOut(10)

        assertFalse(sideBar.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)))
        assertEquals(emptyList<String>(), selected)
    }

    private companion object {
        const val WIDTH = 60
    }
}
