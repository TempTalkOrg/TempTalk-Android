package com.difft.android.call.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class FloatingWindowDragRtlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `an RTL drag to the right moves the window right and releases a rightward offset`() {
        var released: Offset? = null
        var cancelled: Offset? = null
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .absoluteOffset(100.dp, 100.dp)
                            .size(120.dp, 200.dp)
                            .floatingWindowDrag(PipDrag(clamp = { it }, onRelease = { released = it }, onCancel = { cancelled = it })) {}
                            .testTag("window"),
                    )
                }
            }
        }
        val node = composeTestRule.onNodeWithTag("window")
        val startLeft = node.fetchSemanticsNode().boundsInRoot.left
        node.performTouchInput {
            down(center)
            moveBy(Offset(150f, 0f))
            moveBy(Offset(50f, 0f))
        }
        composeTestRule.waitForIdle()
        val midLeft = composeTestRule.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot.left
        assertTrue("follows the finger right: $startLeft -> $midLeft", midLeft > startLeft + 100f)

        composeTestRule.onNodeWithTag("window").performTouchInput { up() }
        composeTestRule.waitForIdle()
        val drop = released
        assertTrue("released to the right: $drop", drop != null && drop.x > 100f)
        assertNull(cancelled)
        assertEquals("back to its place until the stage moves it", startLeft, composeTestRule.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot.left, 0.5f)
    }
}
