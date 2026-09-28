package com.difft.android.base.ui.compose.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * [DifftSettingsOptionRow] contract (issue #1223): a 24dp checkmark exactly while selected, no
 * placeholder slot when unselected, an optional description line, and the whole row tappable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftSettingsOptionRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setRow(
        selected: Boolean,
        description: String? = null,
        label: String = LABEL,
        onClick: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsOptionRow(
                    label = label,
                    selected = selected,
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth().testTag(TAG),
                    description = description,
                )
            }
        }
    }

    // T15
    @Test
    fun `a selected row shows a 24dp checkmark`() {
        setRow(selected = true)

        composeTestRule
            .onNodeWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true)
            .assertExists()
            .assertWidthIsEqualTo(DifftSettingsDefaults.CheckmarkSize)
            .assertHeightIsEqualTo(DifftSettingsDefaults.CheckmarkSize)
    }

    // T16
    @Test
    fun `an unselected row shows neither checkmark nor chevron`() {
        setRow(selected = false)

        composeTestRule
            .onNodeWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithTag(DifftSettingsDefaults.ChevronTestTag, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    // T17
    @Test
    fun `a description renders when passed`() {
        setRow(selected = false, description = DESCRIPTION)

        composeTestRule.onNodeWithText(DESCRIPTION, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(LABEL, useUnmergedTree = true).assertIsDisplayed()
    }

    // T17
    @Test
    fun `no description line exists without one`() {
        setRow(selected = false)

        composeTestRule.onNodeWithText(DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
    }

    // T18
    @Test
    fun `a tap far from the trailing edge fires onClick once`() {
        var clicks = 0
        setRow(selected = true, onClick = { clicks++ })

        composeTestRule.onNodeWithTag(TAG).performTouchInput { click(Offset(4f, height / 2f)) }
        assertEquals(1, clicks)
    }

    // T19
    @Test
    fun `selection does not change the row height`() {
        setRow(selected = true)
        composeTestRule.onNodeWithTag(TAG).assertHeightIsEqualTo(DifftSettingsDefaults.RowMinHeight)
    }

    // T19
    @Test
    fun `an unselected row is the same 52dp as a selected one`() {
        setRow(selected = false)
        composeTestRule.onNodeWithTag(TAG).assertHeightIsEqualTo(DifftSettingsDefaults.RowMinHeight)
    }

    // T19
    @Test
    fun `a described row grows past the row minimum`() {
        setRow(selected = true, description = DESCRIPTION)

        val bounds = composeTestRule.onNodeWithTag(TAG).getBoundsInRoot()
        val height = bounds.bottom.value - bounds.top.value
        assertTrue(
            "expected more than ${DifftSettingsDefaults.RowMinHeight}, measured ${height}dp",
            height > DifftSettingsDefaults.RowMinHeight.value,
        )
    }

    // T20
    @Test
    fun `the label starts at the same edge whether selected or not`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsGroup(modifier = Modifier.fillMaxWidth()) {
                    DifftSettingsOptionRow(
                        label = SELECTED_LABEL,
                        selected = true,
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DifftSettingsOptionRow(
                        label = UNSELECTED_LABEL,
                        selected = false,
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        val selectedLeft = composeTestRule
            .onNodeWithText(SELECTED_LABEL, useUnmergedTree = true).getBoundsInRoot().left.value
        val unselectedLeft = composeTestRule
            .onNodeWithText(UNSELECTED_LABEL, useUnmergedTree = true).getBoundsInRoot().left.value
        assertTrue(
            "label moved: selected at ${selectedLeft}dp, unselected at ${unselectedLeft}dp",
            abs(selectedLeft - unselectedLeft) <= 0.5f,
        )
    }

    // T21
    @Test
    fun `a group of option rows keeps one checkmark and 1dp hairlines`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsGroup(modifier = Modifier.fillMaxWidth()) {
                    repeat(3) { i ->
                        DifftSettingsOptionRow(
                            label = "option $i",
                            selected = i == 1,
                            onClick = {},
                            modifier = Modifier.fillMaxWidth().testTag("row_$i"),
                        )
                    }
                }
            }
        }

        composeTestRule
            .onAllNodesWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true)
            .assertCountEquals(1)
        assertGap("row_0", "row_1")
        assertGap("row_1", "row_2")
    }

    private fun assertGap(topTag: String, bottomTag: String) {
        val top = composeTestRule.onNodeWithTag(topTag).getBoundsInRoot()
        val bottom = composeTestRule.onNodeWithTag(bottomTag).getBoundsInRoot()
        val gap = bottom.top.value - top.bottom.value
        assertTrue(
            "expected a ${DifftSettingsDefaults.SeparatorThickness} hairline, measured ${gap}dp",
            abs(gap - DifftSettingsDefaults.SeparatorThickness.value) <= 0.5f,
        )
    }

    private companion object {
        const val TAG = "difft_settings_option_row"
        const val LABEL = "Always"
        const val DESCRIPTION = "Only when mentioned"
        const val SELECTED_LABEL = "SelectedProbe"
        const val UNSELECTED_LABEL = "UnselectedProbe"
    }
}
