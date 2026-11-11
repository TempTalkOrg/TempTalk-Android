package com.difft.android.chat.presend

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w400dp-h800dp-mdpi")
class FilePreSendScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `light file composer only gains an outline in confidential mode`() = assertComposer(false)

    @Test
    fun `dark file composer only gains an outline in confidential mode`() = assertComposer(true)

    private fun assertComposer(dark: Boolean) {
        val mode = mutableIntStateOf(0)
        var sent: String? = null
        lateinit var view: View
        composeRule.setContent {
            DifftTheme(darkTheme = dark) {
                view = LocalView.current
                FilePreSendScreen(
                    fileName = "report.txt", fileSize = 100,
                    confidentialMode = mode.intValue,
                    showConfidentialToggle = true,
                    onBack = {}, onConfidentialToggle = {}, onSend = { sent = it },
                )
            }
        }
        val field = composeRule.onNodeWithTag(FILE_PRESEND_INPUT_TAG, useUnmergedTree = true)
        val screen = composeRule.onNodeWithTag(FILE_PRESEND_SCREEN_TAG)
        val send = composeRule.onAllNodes(hasClickAction()).onLast()
        val images = mutableListOf<Bitmap>()
        fun capture(node: SemanticsNodeInteraction) = drawRegion(view, node).also { images += it }
        try {
            val plainField = capture(field)
            val plainScreen = capture(screen)
            val plainSend = capture(send)
            assertTrue(!hasBlueEdge(plainField))
            composeRule.runOnIdle { mode.intValue = 1 }
            val confidentialField = capture(field)
            assertTrue("confidential field must have blue dashes", hasBlueEdge(confidentialField))
            assertEquals(plainField.getPixel(4, plainField.height / 2), confidentialField.getPixel(4, confidentialField.height / 2))
            val confidentialScreen = capture(screen)
            assertEquals(plainScreen.getPixel(0, plainScreen.height - 2), confidentialScreen.getPixel(0, confidentialScreen.height - 2))
            assertSamePixels(plainSend, capture(send))
            composeRule.runOnIdle { mode.intValue = 0 }
            assertTrue("off removes the field border", !hasBlueEdge(capture(field)))
            assertSamePixels(plainScreen, capture(screen))
            send.assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp).performClick()
            assertEquals("", sent)
        } finally {
            images.forEach { it.recycle() }
        }
    }

    
    private fun drawRegion(view: View, node: SemanticsNodeInteraction): Bitmap {
        composeRule.waitForIdle()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt()
        val width = bounds.right.roundToInt() - left
        val height = bounds.bottom.roundToInt() - top
        assertTrue("the node must have laid-out bounds", width > 0 && height > 0)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            composeRule.runOnUiThread {
                val canvas = Canvas(bitmap)
                canvas.translate(-left.toFloat(), -top.toFloat())
                view.draw(canvas)
            }
        }
    }

    
    private fun hasBlueEdge(image: Bitmap): Boolean =
        (0 until minOf(2, image.height)).any { y ->
            (0 until image.width).any { x ->
                val c = image.getPixel(x, y)
                android.graphics.Color.blue(c) - android.graphics.Color.red(c) > BLUE_MARGIN
            }
        }

    private companion object {
        const val BLUE_MARGIN = 40
    }

    private fun assertSamePixels(expected: Bitmap, actual: Bitmap) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertTrue("all region pixels must match", expected.sameAs(actual))
    }
}
