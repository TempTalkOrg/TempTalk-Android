package com.difft.android.call.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class CallRingFrameRtlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the callee stays in your card on the right of an off-centre book fold in RTL`() {
        val px = 2 
        
        val area = IntRect(220 * px, 0, 400 * px, 800 * px)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.size(400.dp, 800.dp)) {
                    RingFrame(area) { Box(Modifier.size(20.dp).testTag("callee")) }
                }
            }
        }
        val callee = composeTestRule.onNodeWithTag("callee").fetchSemanticsNode().boundsInRoot
        assertEquals("centred across x 220–400dp", 300f * px, callee.left, 1f)
        assertEquals("centred down the card", 390f * px, callee.top, 1f)
    }
}
