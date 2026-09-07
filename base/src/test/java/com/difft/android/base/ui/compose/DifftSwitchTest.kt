package com.difft.android.base.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [DifftSwitch] contract (issue #1206): the non-interactive form is exactly the Figma 51×31 pill,
 * the interactive form exposes switch semantics with a 48dp-tall touch target and toggles on click.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftSwitchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `visual-only form is the 51 by 31 design box`() {
        composeTestRule.setContent {
            DifftTheme { DifftSwitch(checked = true, onCheckedChange = null, modifier = Modifier.testTag(TAG)) }
        }

        composeTestRule.onNodeWithTag(TAG)
            .assertWidthIsEqualTo(DifftSwitchDefaults.TrackWidth)
            .assertHeightIsEqualTo(DifftSwitchDefaults.TrackHeight)
    }

    @Test
    fun `interactive form has a 48dp touch target height and toggles on click`() {
        composeTestRule.setContent {
            var checked by remember { mutableStateOf(false) }
            DifftTheme {
                DifftSwitch(checked = checked, onCheckedChange = { checked = it }, modifier = Modifier.testTag(TAG))
            }
        }
        val node = composeTestRule.onNodeWithTag(TAG)

        node.assertWidthIsEqualTo(DifftSwitchDefaults.TrackWidth)
            .assertHeightIsEqualTo(48.dp)
            .assertIsOff()
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
                DifftSwitch(
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

    private companion object {
        const val TAG = "difft_switch"
    }
}
