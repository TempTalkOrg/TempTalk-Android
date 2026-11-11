package com.difft.android.call.ui.tabletop

import android.graphics.Rect
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import com.difft.android.call.service.TestScopeApplication
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config





@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [30])
class CallFoldTrackerMappingTest {

    private fun feature(
        state: FoldingFeature.State,
        orientation: FoldingFeature.Orientation,
        occlusion: FoldingFeature.OcclusionType,
        bounds: Rect,
    ): FoldingFeature = mockk {
        every { this@mockk.state } returns state
        every { this@mockk.orientation } returns orientation
        every { occlusionType } returns occlusion
        every { this@mockk.bounds } returns bounds
    }

    @Test
    fun `half open horizontal fold maps with its window bounds`() {
        val fold = feature(
            FoldingFeature.State.HALF_OPENED,
            FoldingFeature.Orientation.HORIZONTAL,
            FoldingFeature.OcclusionType.NONE,
            Rect(0, 1182, 1672, 1182),
        ).toCallFold()
        assertEquals(CallFold(CallFoldState.HALF_OPENED, horizontal = true, occluding = false, bounds = PxRect(0, 1182, 1672, 1182)), fold)
    }

    @Test
    fun `flat vertical full-occlusion hinge maps too`() {
        val fold = feature(
            FoldingFeature.State.FLAT,
            FoldingFeature.Orientation.VERTICAL,
            FoldingFeature.OcclusionType.FULL,
            Rect(1170, 0, 1194, 1672),
        ).toCallFold()
        assertEquals(CallFold(CallFoldState.FLAT, horizontal = false, occluding = true, bounds = PxRect(1170, 0, 1194, 1672)), fold)
    }

    @Test
    fun `only folding features enter the report`() {
        val other: DisplayFeature = mockk { every { bounds } returns Rect(0, 0, 10, 10) }
        val fold = feature(
            FoldingFeature.State.HALF_OPENED,
            FoldingFeature.Orientation.HORIZONTAL,
            FoldingFeature.OcclusionType.NONE,
            Rect(0, 1182, 1672, 1182),
        )
        val info = WindowLayoutInfo(listOf(other, fold))
        assertEquals(1, info.toReport().folds.size)
        assertEquals(CallFoldReport(emptyList()), WindowLayoutInfo(emptyList()).toReport())
    }

    @Test
    fun `log line lists every fold with the window size`() {
        val report = CallFoldReport(listOf(CallFold(CallFoldState.HALF_OPENED, true, false, PxRect(0, 1182, 1672, 1182))))
        assertEquals("[Call] fold n=1 f0=HALF_OPENED/H/occl=false/0,1182,1672,1182 winPx=1672x2364", formatFoldLog(report, 1672, 2364))
        assertEquals("[Call] fold n=0 winPx=1168x1712", formatFoldLog(CallFoldReport(emptyList()), 1168, 1712))
    }
}
