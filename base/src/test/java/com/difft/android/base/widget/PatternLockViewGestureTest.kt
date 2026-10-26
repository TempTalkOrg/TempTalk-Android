package com.difft.android.base.widget

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.utils.ApplicationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Gesture handling of the real view on a 260px grid (mdpi): dot centres at 16 / 130 / 244 on both
 * axes, ids `row * 3 + column`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "mdpi", application = PatternLockViewGestureTest.TestScopeApplication::class)
class PatternLockViewGestureTest {

    class TestScopeApplication : ScopeApplication() {
        override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default
    }

    private lateinit var view: PatternLockView
    private val completed = mutableListOf<List<Int>>()
    private var started = 0
    private var progress = 0
    private var cleared = 0
    private var downTime = 0L

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<TestScopeApplication>()
        ApplicationHelper.init(app)
        // Attached to a window: delayed runnables posted on a detached view never run.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = PatternLockView(activity)
        activity.setContentView(view, ViewGroup.LayoutParams(260, 260))
        view.measure(MeasureSpec.makeMeasureSpec(260, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(260, MeasureSpec.EXACTLY))
        view.layout(0, 0, 260, 260)
        view.addPatternLockListener(object : PatternLockView.PatternLockViewListener {
            override fun onStarted() {
                started++
            }

            override fun onProgress(progressPattern: MutableList<PatternLockView.Dot>) {
                progress++
            }

            override fun onComplete(pattern: MutableList<PatternLockView.Dot>) {
                completed += pattern.map { it.id }
            }

            override fun onCleared() {
                cleared++
            }
        })
    }

    private fun dot(id: Int) = 16f + (id % 3) * 114f to 16f + (id / 3) * 114f

    private fun event(action: Int, x: Float, y: Float, time: Long = SystemClock.uptimeMillis()): MotionEvent {
        if (action == MotionEvent.ACTION_DOWN) downTime = time
        return MotionEvent.obtain(downTime, time, action, x, y, 0)
    }

    private fun touchDown(id: Int) = view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, dot(id).first, dot(id).second))

    private fun moveTo(id: Int) = view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, dot(id).first, dot(id).second))

    private fun release(id: Int) = view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, dot(id).first, dot(id).second))

    @Test
    fun `T8 historical samples of one move event are hit tested along the path`() {
        touchDown(0)
        val now = SystemClock.uptimeMillis()
        // One batched event: the earlier sample is beside dot 1 (26px from its centre), the current
        // one is on dot 2. Legacy selection only looks at the current position.
        val move = MotionEvent.obtain(downTime, now, MotionEvent.ACTION_MOVE, 140f, 40f, 0)
        move.addBatch(now + 8, dot(2).first, dot(2).second, 1f, 1f, 0)
        view.dispatchTouchEvent(move)
        release(2)

        assertEquals(listOf(listOf(0, 1, 2)), completed)
        assertEquals(listOf(0, 2), view.legacyPattern.map { it.id })
    }

    @Test
    fun `T7 a cancelled gesture is cleared and never reported as complete`() {
        touchDown(0)
        moveTo(1)
        moveTo(2)
        val clearedBefore = cleared

        view.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, dot(2).first, dot(2).second))

        assertTrue(completed.isEmpty())
        assertEquals(clearedBefore + 1, cleared)
        assertTrue(view.legacyPattern.isEmpty())
    }

    @Test
    fun `T1 a new touch never merges with the retained selection of the previous gesture`() {
        touchDown(0)
        moveTo(1)
        release(1)
        // The caller has not cleared (set screen clears immediately, unlock after a delay).
        touchDown(7)
        moveTo(8)
        release(8)

        assertEquals(listOf(listOf(0, 1), listOf(7, 8)), completed)
    }

    @Test
    fun `a new touch cancels the pending delayed clear`() {
        touchDown(0)
        moveTo(1)
        release(1)
        view.clearPatternDelayed(1000)

        touchDown(3)
        moveTo(4)
        shadowOf(Looper.getMainLooper()).idleFor(1500, TimeUnit.MILLISECONDS)
        moveTo(5)
        release(5)

        assertEquals(listOf(listOf(0, 1), listOf(3, 4, 5)), completed)
    }

    @Test
    fun `a delayed clear that is not interrupted clears the selection`() {
        touchDown(0)
        moveTo(1)
        release(1)
        view.clearPatternDelayed(1000)
        val clearedBefore = cleared

        shadowOf(Looper.getMainLooper()).idleFor(1100, TimeUnit.MILLISECONDS)

        assertEquals(clearedBefore + 1, cleared)
    }

    @Test
    fun `a touch on empty space reports nothing`() {
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 73f, 73f))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 73f, 73f))

        assertEquals(0, started)
        assertTrue(completed.isEmpty())
    }

    @Test
    fun `T16 passing over a used dot reports no progress and no second start`() {
        touchDown(0)
        moveTo(1)
        moveTo(0)
        moveTo(1)
        release(1)

        assertEquals(1, started)
        assertEquals(1, progress)
        assertEquals(listOf(listOf(0, 1)), completed)
    }
}
