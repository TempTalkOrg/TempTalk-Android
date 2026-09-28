package com.difft.android.base.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [DifftCheckBox] contract (issues #1203, #1237): the non-interactive form is exactly the library's
 * 20dp box, the interactive form exposes checkbox semantics with a 48dp touch target and toggles on
 * click, and the labelled row toggles as a whole while keeping the help icon's clicks to itself.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftCheckBoxTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `visual-only form is the 20dp design box`() {
        composeTestRule.setContent {
            DifftTheme { DifftCheckBox(checked = true, onCheckedChange = null, modifier = Modifier.testTag(TAG)) }
        }

        composeTestRule.onNodeWithTag(TAG)
            .assertWidthIsEqualTo(DifftCheckBoxDefaults.Size)
            .assertHeightIsEqualTo(DifftCheckBoxDefaults.Size)
    }

    @Test
    fun `interactive form has a 48dp touch target and toggles on click`() {
        composeTestRule.setContent {
            var checked by remember { mutableStateOf(false) }
            DifftTheme {
                DifftCheckBox(checked = checked, onCheckedChange = { checked = it }, modifier = Modifier.testTag(TAG))
            }
        }
        val node = composeTestRule.onNodeWithTag(TAG)

        node.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).assertIsOff()
        node.performClick()
        node.assertIsOn()
        node.performClick()
        node.assertIsOff()
    }

    @Test
    fun `disabled interactive form does not toggle`() {
        composeTestRule.setContent {
            var checked by remember { mutableStateOf(false) }
            DifftTheme {
                DifftCheckBox(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    enabled = false,
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
        val node = composeTestRule.onNodeWithTag(TAG)

        node.performClick()
        node.assertIsOff()
    }

    @Test
    fun `labelled row shows the label and toggles as a whole`() {
        composeTestRule.setContent {
            var checked by remember { mutableStateOf(false) }
            DifftTheme {
                DifftCheckBox(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    modifier = Modifier.testTag(TAG),
                    label = LABEL,
                )
            }
        }
        val node = composeTestRule.onNodeWithTag(TAG)
        composeTestRule.onNodeWithText(LABEL).assertExists()

        node.assertIsOff()
        node.performClick()
        node.assertIsOn()
    }

    @Test
    fun `labelled row stays content-height so fixed-height hosts keep fitting their rows`() {
        composeTestRule.setContent {
            DifftTheme {
                DifftCheckBox(checked = false, onCheckedChange = {}, modifier = Modifier.testTag(TAG), label = LABEL)
            }
        }

        val height = composeTestRule.onNodeWithTag(TAG).getUnclippedBoundsInRoot().height
        assertTrue("labelled row must not take the 48dp touch minimum, was $height", height < 48.dp)
    }

    @Test
    fun `help icon reports its own clicks without toggling the row`() {
        var helpClicks = 0
        composeTestRule.setContent {
            var checked by remember { mutableStateOf(false) }
            DifftTheme {
                DifftCheckBox(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    modifier = Modifier.testTag(TAG),
                    label = LABEL,
                    onHelpClick = { helpClicks++ },
                )
            }
        }
        val node = composeTestRule.onNodeWithTag(TAG)

        // The help icon is the trailing element of the row, so the right edge is inside it.
        node.performTouchInput { click(Offset(width - 1f, height / 2f)) }

        assertEquals(1, helpClicks)
        node.assertIsOff()
    }

    private companion object {
        const val TAG = "difft_checkbox"
        const val LABEL = "Everyone can invite others"
    }
}
