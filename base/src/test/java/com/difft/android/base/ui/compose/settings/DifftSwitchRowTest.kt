package com.difft.android.base.ui.compose.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.difft.android.base.ui.compose.DifftSwitchDefaults
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode







@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftSwitchRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a tap on the label side of the row toggles it`() {
        composeTestRule.setContent {
            var checked by remember { mutableStateOf(false) }
            DifftTheme {
                DifftSwitchRow(
                    label = "L",
                    checked = checked,
                    onCheckedChange = { checked = it },
                    modifier = Modifier.fillMaxWidth().testTag(TAG),
                )
            }
        }
        val node = composeTestRule.onNodeWithTag(TAG)

        node.assertIsOff()
        
        node.performTouchInput { click(Offset(4f, height / 2f)) }
        node.assertIsOn()
    }

    @Test
    fun `a null callback leaves the row inert`() {
        composeTestRule.setContent {
            DifftTheme {
                DifftSwitchRow(
                    label = "L",
                    checked = false,
                    onCheckedChange = null,
                    modifier = Modifier.fillMaxWidth().testTag(TAG),
                )
            }
        }
        val node = composeTestRule.onNodeWithTag(TAG)

        node.performTouchInput { click(Offset(4f, height / 2f)) }
        node.assertHasNoClickAction()
        node.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
    }

    @Test
    fun `minHeight zero keeps the row compact`() {
        composeTestRule.setContent {
            DifftTheme {
                DifftSwitchRow(
                    label = "L",
                    checked = false,
                    onCheckedChange = null,
                    minHeight = 0.dp,
                    modifier = Modifier.fillMaxWidth().testTag(TAG),
                )
            }
        }

        val height = composeTestRule.onNodeWithTag(TAG).getBoundsInRoot().height
        
        
        assertTrue(height >= DifftSwitchDefaults.TrackHeight)
        assertTrue(height < DifftSettingsDefaults.RowMinHeight)
    }

    private companion object {
        const val TAG = "difft_switch_row"
    }
}
