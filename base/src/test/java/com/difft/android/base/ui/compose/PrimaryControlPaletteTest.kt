package com.difft.android.base.ui.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "mdpi")
class PrimaryControlPaletteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `light controls pair primary and onPrimary`() = assertControls(dark = false)

    @Test
    fun `dark controls pair primary and onPrimary`() = assertControls(dark = true)

    private fun assertControls(dark: Boolean) {
        lateinit var view: View
        var buttonContent = Color.Unspecified
        val presses = MutableInteractionSource()
        composeRule.setContent {
            DifftTheme(darkTheme = dark) {
                view = LocalView.current
                Column {
                    DifftSwitch(checked = true, onCheckedChange = null, modifier = Modifier.testTag("switch"))
                    DifftCheckBox(checked = true, onCheckedChange = null, modifier = Modifier.testTag("check"))
                    DifftButton(onClick = null, interactionSource = presses, modifier = Modifier.testTag("button")) {
                        val color = LocalContentColor.current
                        SideEffect { buttonContent = color }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val primary = Color(if (dark) 0xFFEAECEF else 0xFF1E2329)
        val onPrimary = Color(if (dark) 0xFF181A20 else 0xFFFFFFFF)
        assertEquals(onPrimary, buttonContent)
        assertContainsColor(view, "switch", primary, minimum = 100)
        assertContainsColor(view, "switch", onPrimary, minimum = 100)
        assertContainsColor(view, "check", primary, minimum = 100)
        assertContainsColor(view, "check", onPrimary, minimum = 5)
        assertContainsColor(view, "button", primary, minimum = 100)

        val press = PressInteraction.Press(Offset.Zero)
        composeRule.runOnIdle { assertTrue(presses.tryEmit(press)) }
        composeRule.waitForIdle()
        val pressed = Color(if (dark) 0xFFB7BDC6 else 0xFF474D57)
        assertContainsColor(view, "button", pressed, minimum = 100)
        composeRule.runOnIdle { assertTrue(presses.tryEmit(PressInteraction.Release(press))) }
        composeRule.waitForIdle()
        assertContainsColor(view, "button", primary, minimum = 100)
    }

    private fun assertContainsColor(view: View, tag: String, color: Color, minimum: Int) {
        val bounds = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            composeRule.runOnUiThread { view.draw(Canvas(bitmap)) }
            var count = 0
            for (y in bounds.top.value.toInt() until bounds.bottom.value.toInt()) {
                for (x in bounds.left.value.toInt() until bounds.right.value.toInt()) {
                    if (bitmap.getPixel(x, y) == color.toArgb()) count++
                }
            }
            assertTrue("$tag should contain $color (found $count pixels, expected >= $minimum)", count >= minimum)
        } finally {
            bitmap.recycle()
        }
    }
}
