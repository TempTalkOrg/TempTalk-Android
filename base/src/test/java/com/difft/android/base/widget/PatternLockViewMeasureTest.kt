package com.difft.android.base.widget

import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.utils.ApplicationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

/** Grid sizing: square of the smaller available side, capped at 260dp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "mdpi", application = PatternLockViewMeasureTest.TestScopeApplication::class)
class PatternLockViewMeasureTest {

    class TestScopeApplication : ScopeApplication() {
        override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default
    }

    private lateinit var view: PatternLockView

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<TestScopeApplication>()
        ApplicationHelper.init(app)
        view = PatternLockView(app)
    }

    // mdpi: 1dp == 1px
    private fun measure(width: Int, height: Int): Pair<Int, Int> {
        view.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
        )
        return view.measuredWidth to view.measuredHeight
    }

    @Test
    fun `wide window is capped at 260dp`() {
        // Xiaomi 18 Fold inner screen: 608dp wide minus 56dp side margins.
        assertEquals(260 to 260, measure(496, 700))
    }

    @Test
    fun `common phone width is capped at 260dp`() {
        // 412dp, 424dp (Xiaomi 18 Fold outer screen) and 448dp phones minus 56dp side margins.
        assertEquals(260 to 260, measure(300, 600))
        assertEquals(260 to 260, measure(312, 600))
        assertEquals(260 to 260, measure(336, 600))
    }

    @Test
    fun `narrow window keeps its full width`() {
        // 360dp phone minus 56dp side margins.
        assertEquals(248 to 248, measure(248, 600))
    }

    @Test
    fun `short window is bounded by height`() {
        assertEquals(200 to 200, measure(496, 200))
    }
}
