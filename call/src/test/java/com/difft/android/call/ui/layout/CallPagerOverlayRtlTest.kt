package com.difft.android.call.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.difft.android.call.layout.CallLayoutController
import com.difft.android.call.layout.Pager
import com.difft.android.call.layout.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The page indicator keeps the plan's absolute positions in an RTL locale, like the cards (guide §5.1). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class CallPagerOverlayRtlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the bar and the page pill stay at the right edge in RTL`() {
        val area = Rect(0.0, 0.0, 400.0, 800.0)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.size(400.dp, 800.dp)) {
                    CallPagerOverlay(Pager(page = 0, pages = 3, later = 9, area = area), null, Offset.Zero, CallLayoutController(), PagerPillMemory())
                }
            }
        }
        val px = composeTestRule.density.density
        val bar = pagerBar(area, 3)
        val segment = composeTestRule.onNodeWithTag("call_pager_segment_0").fetchSemanticsNode().boundsInRoot
        assertEquals("segment left edge", (bar.x * px).toFloat(), segment.left, 1f)
        assertEquals("segment top", (bar.segmentTop(0) * px).toFloat(), segment.top, 1f)

        val pill = composeTestRule.onNodeWithText("1").fetchSemanticsNode().boundsInRoot
        assertTrue("the pill is left of the bar: ${pill.right} vs ${segment.left}", pill.right <= segment.left)
        assertTrue("and beside it, not at the far edge: ${pill.left}", pill.left > 300 * px)
    }
}
