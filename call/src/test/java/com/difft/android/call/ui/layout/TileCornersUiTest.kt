package com.difft.android.call.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.difft.android.call.layout.Rect as LayoutRect
import com.difft.android.call.layout.Size as LayoutSize





@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class TileCornersUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun insetBounds(direction: LayoutDirection, end: Boolean) = run {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.size(400.dp, 200.dp)) {
                    Box(
                        Modifier
                            .align(if (end) Alignment.TopEnd else Alignment.BottomStart)
                            .sideInset(end) { 10.dp }
                            .size(50.dp, 20.dp)
                            .testTag("element"),
                    )
                }
            }
        }
        val px = composeTestRule.density.density
        composeTestRule.onNodeWithTag("element").fetchSemanticsNode().boundsInRoot.let { it.left / px to it.right / px }
    }

    @Test
    fun `the name pill moves in from the start, the left in LTR`() {
        assertEquals(10f, insetBounds(LayoutDirection.Ltr, end = false).first, 0.5f)
    }

    @Test
    fun `the name pill moves in from the start, the right in RTL`() {
        assertEquals(390f, insetBounds(LayoutDirection.Rtl, end = false).second, 0.5f)
    }

    @Test
    fun `the badge moves in from the end, the right in LTR`() {
        assertEquals(390f, insetBounds(LayoutDirection.Ltr, end = true).second, 0.5f)
    }

    @Test
    fun `the badge moves in from the end, the left in RTL`() {
        assertEquals(10f, insetBounds(LayoutDirection.Rtl, end = true).first, 0.5f)
    }

    private fun pillBounds(direction: LayoutDirection) = run {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.size(400.dp, 200.dp)) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .pillInsets(start = { 10.dp }, end = { 30.dp })
                            .fillMaxWidth()
                            .height(20.dp)
                            .testTag("pill"),
                    )
                }
            }
        }
        val px = composeTestRule.density.density
        composeTestRule.onNodeWithTag("pill").fetchSemanticsNode().boundsInRoot.let { it.left / px to it.right / px }
    }

    @Test
    fun `a long name pill starts in from the start and grows no closer to the end than its end inset, LTR`() {
        val (left, right) = pillBounds(LayoutDirection.Ltr)
        assertEquals(10f, left, 0.5f)
        assertEquals(370f, right, 0.5f)
    }

    @Test
    fun `a long name pill starts in from the start and grows no closer to the end than its end inset, RTL`() {
        val (left, right) = pillBounds(LayoutDirection.Rtl)
        assertEquals(30f, left, 0.5f)
        assertEquals(390f, right, 0.5f)
    }

    @Test
    fun `a card's corners follow it into and out of the screen's corner`() {
        val state = TileCornerState()
        var at by mutableStateOf(Offset(100f, 300f))
        var root = Offset.Zero
        composeTestRule.setContent {
            val density = remember { composeTestRule.density.density }
            Box(Modifier.fillMaxSize().onGloballyPositioned { root = it.positionInWindow() / density }) {
                Box(
                    Modifier
                        .absoluteOffset(at.x.dp, at.y.dp)
                        .size(120.dp, 160.dp)
                        .onGloballyPositioned { state.place(it, density) },
                )
            }
        }
        val screen = CallScreenCorners(LayoutSize(400.0, 1000.0), CornerRadii(80.0))
        composeTestRule.waitForIdle()
        assertNull("away from the corners it keeps its own", state.radii(screen))

        at = Offset(4f, 0f)
        composeTestRule.waitForIdle()
        val expected = cardCorners(LayoutRect(root.x + 4.0, root.y.toDouble(), 120.0, 160.0), screen)
        val radii = state.radii(screen)
        assertNotNull(radii)
        assertEquals(expected!!.topLeft, radii!!.topLeft, 0.5)
        assertNull("without the screen's corners it keeps its own", state.radii(null))

        at = Offset(100f, 300f)
        composeTestRule.waitForIdle()
        assertNull(state.radii(screen))
    }
}
