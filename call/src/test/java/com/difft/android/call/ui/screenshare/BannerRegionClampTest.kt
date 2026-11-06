package com.difft.android.call.ui.screenshare

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.difft.android.call.service.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The speaker banner's saved drag offset stays inside its parent region. Entering tabletop shrinks
 * that region to the stage above the fold without resizing the banner, so the offset must be
 * re-clamped when the region changes, not only on a drag or a banner resize.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [30], qualifiers = "w608dp-h860dp-port-mdpi")
class BannerRegionClampTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `clamp keeps the banner inside the region`() {
        val bounds = BannerBounds(
            parentMaxWPx = 608f,
            parentMaxHPx = 410f,
            marginEndPx = 15f,
            marginTopPx = 17f,
            bannerWidthPx = 160f,
            bannerHeightPx = 40f,
        )
        // Max dy = 410 - 40 - 17 = 353.
        assertEquals(0f to 353f, clampBannerDragDelta(0f, 600f, bounds))
        assertEquals(0f to -17f, clampBannerDragDelta(0f, -100f, bounds))
        // Min dx = -(608 - 160 - 15) = -433, max dx = 15.
        assertEquals(-433f to 0f, clampBannerDragDelta(-1000f, 0f, bounds))
        assertEquals(15f to 0f, clampBannerDragDelta(100f, 0f, bounds))
    }

    @Test
    fun `a shrinking region re-clamps a saved offset below it`() {
        var parentHeight by mutableFloatStateOf(860f)
        var dragY by mutableFloatStateOf(600f)
        rule.setContent {
            BannerContainer(
                modifier = Modifier,
                onClick = {},
                dragDeltaX = 0f,
                dragDeltaY = dragY,
                parentMaxWPx = 608f,
                parentMaxHPx = parentHeight,
                marginEndPx = 15f,
                marginTopPx = 17f,
                bannerWidthPx = 160f,
                onDragDeltaChange = { _, dy -> dragY = dy },
            ) {
                Box(Modifier.size(160.dp, 40.dp))
            }
        }
        rule.waitForIdle()
        assertEquals("fits in the full window", 600f, dragY, 0f)

        parentHeight = 410f
        rule.waitForIdle()
        assertEquals("re-clamped to the stage above the fold", 410f - 40f - 17f, dragY, 0f)
    }
}
