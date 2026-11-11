package com.difft.android.base.ui.compose.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
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
@Config(sdk = [34])
class DifftSettingsRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    
    @Test
    fun `a single-line row is 52dp tall`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsRow(label = "L", modifier = Modifier.fillMaxWidth().testTag(TAG))
            }
        }

        composeTestRule.onNodeWithTag(TAG).assertHeightIsEqualTo(DifftSettingsDefaults.RowMinHeight)
    }

    
    @Test
    fun `label and value both render`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsRow(
                    label = "Version",
                    value = "2.4.0",
                    modifier = Modifier.fillMaxWidth().testTag(TAG),
                )
            }
        }

        composeTestRule.onNodeWithText("Version").assertIsDisplayed()
        composeTestRule.onNodeWithText("2.4.0").assertIsDisplayed()
        
        val label = composeTestRule.onNodeWithText("Version").getBoundsInRoot()
        val value = composeTestRule.onNodeWithText("2.4.0").getBoundsInRoot()
        assertTrue("value should sit after the label", value.left.value > label.left.value)
    }

    
    @Test
    fun `a tap far from the trailing edge fires onClick once`() {
        var clicks = 0
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsRow(
                    label = "L",
                    onClick = { clicks++ },
                    modifier = Modifier.fillMaxWidth().testTag(TAG),
                )
            }
        }

        composeTestRule.onNodeWithTag(TAG).performTouchInput { click(Offset(4f, height / 2f)) }
        assertEquals(1, clicks)
    }

    
    @Test
    fun `a row without onClick is inert and shows no chevron`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsRow(label = "L", modifier = Modifier.fillMaxWidth().testTag(TAG))
            }
        }

        composeTestRule.onNodeWithTag(TAG).assertHasNoClickAction()
        composeTestRule
            .onNodeWithTag(DifftSettingsDefaults.ChevronTestTag, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    
    @Test
    fun `a clickable row shows a 16dp chevron`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsRow(
                    label = "L",
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().testTag(TAG),
                )
            }
        }

        composeTestRule
            .onNodeWithTag(DifftSettingsDefaults.ChevronTestTag, useUnmergedTree = true)
            .assertExists()
            .assertWidthIsEqualTo(DifftSettingsDefaults.ChevronSize)
    }

    
    @Test
    fun `a trailing slot displaces the chevron`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsRow(
                    label = "L",
                    onClick = {},
                    trailing = { Text(TRAILING) },
                    modifier = Modifier.fillMaxWidth().testTag(TAG),
                )
            }
        }

        composeTestRule.onNodeWithText(TRAILING).assertExists()
        composeTestRule
            .onNodeWithTag(DifftSettingsDefaults.ChevronTestTag, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    
    @Test
    fun `a disabled row swallows the tap`() {
        var clicks = 0
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsRow(
                    label = "L",
                    onClick = { clicks++ },
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().testTag(TAG),
                )
            }
        }

        val node = composeTestRule.onNodeWithTag(TAG)
        node.assertIsNotEnabled()
        node.performTouchInput { click(Offset(4f, height / 2f)) }
        assertEquals(0, clicks)
    }

    
    @Test
    fun `in RTL the chevron sits at the leading edge`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                DifftTheme(applyWindowBackground = false) {
                    DifftSettingsRow(
                        label = "RtlLabel",
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().testTag(TAG),
                    )
                }
            }
        }

        val chevron = composeTestRule
            .onNodeWithTag(DifftSettingsDefaults.ChevronTestTag, useUnmergedTree = true)
            .getBoundsInRoot()
        
        val label = composeTestRule.onNodeWithText("RtlLabel", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(
            "chevron ${chevron.left} should precede label ${label.left} in RTL",
            chevron.left.value < label.left.value,
        )
    }

    private companion object {
        const val TAG = "difft_settings_row"
        const val TRAILING = "TrailingProbe"
    }
}
