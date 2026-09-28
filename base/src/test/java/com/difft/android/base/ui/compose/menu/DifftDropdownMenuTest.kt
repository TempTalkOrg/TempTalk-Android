package com.difft.android.base.ui.compose.menu

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Shared menu shell contract (issue #1237): the row is the library's 44dp "App" row, the divider
 * item is 8dp tall, the panel never falls under its 180dp minimum, and a disabled row swallows
 * clicks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftDropdownMenuTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `row is the 44dp library row inside a 180dp panel`() {
        composeTestRule.setContent {
            DifftTheme {
                DifftDropdownMenuPanel(modifier = Modifier.testTag(PANEL_TAG)) {
                    DifftDropdownMenuItem(
                        text = "Delete",
                        onClick = {},
                        modifier = Modifier.testTag(ITEM_TAG),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(ITEM_TAG)
            .assertHeightIsEqualTo(DifftDropdownMenuDefaults.ItemHeight)
        composeTestRule.onNodeWithTag(PANEL_TAG)
            .assertWidthIsAtLeast(DifftDropdownMenuDefaults.MinWidth)
    }

    @Test
    fun `divider item is 8dp tall`() {
        composeTestRule.setContent {
            DifftTheme {
                DifftDropdownMenuPanel {
                    DifftDropdownMenuItem(text = "Mute", onClick = {})
                    DifftDropdownMenuDivider(modifier = Modifier.testTag(DIVIDER_TAG))
                }
            }
        }

        composeTestRule.onNodeWithTag(DIVIDER_TAG)
            .assertHeightIsEqualTo(DifftDropdownMenuDefaults.DividerHeight)
    }

    @Test
    fun `enabled row clicks through and a disabled row does not`() {
        var enabledClicks = 0
        var disabledClicks = 0
        composeTestRule.setContent {
            DifftTheme {
                DifftDropdownMenuPanel {
                    DifftDropdownMenuItem(
                        text = "Mute",
                        onClick = { enabledClicks++ },
                        modifier = Modifier.testTag(ITEM_TAG),
                    )
                    DifftDropdownMenuItem(
                        text = "Unavailable",
                        onClick = { disabledClicks++ },
                        enabled = false,
                        modifier = Modifier.testTag(DISABLED_TAG),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(ITEM_TAG).performClick()
        composeTestRule.onNodeWithTag(DISABLED_TAG).performClick()

        assertEquals(1, enabledClicks)
        assertEquals(0, disabledClicks)
    }

    private companion object {
        const val PANEL_TAG = "difft_dropdown_menu_panel"
        const val ITEM_TAG = "difft_dropdown_menu_item"
        const val DISABLED_TAG = "difft_dropdown_menu_item_disabled"
        const val DIVIDER_TAG = "difft_dropdown_menu_divider"
    }
}
