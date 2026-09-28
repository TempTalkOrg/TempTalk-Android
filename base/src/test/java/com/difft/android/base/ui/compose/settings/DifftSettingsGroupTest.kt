package com.difft.android.base.ui.compose.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * [DifftSettingsGroup] contract (issue #1209): the 1dp divider hairlines are produced by the
 * group's own spacing, so a caller inserts no dividers and a single-row group has none.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftSettingsGroupTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun rowTag(index: Int) = "row_$index"

    private fun assertGap(topTag: String, bottomTag: String) {
        val top = composeTestRule.onNodeWithTag(topTag).getBoundsInRoot()
        val bottom = composeTestRule.onNodeWithTag(bottomTag).getBoundsInRoot()
        val gap = bottom.top.value - top.bottom.value
        assertTrue(
            "expected a ${DifftSettingsDefaults.SeparatorThickness} hairline, measured ${gap}dp",
            abs(gap - DifftSettingsDefaults.SeparatorThickness.value) <= 0.5f,
        )
    }

    // T8
    @Test
    fun `three rows are separated by two 1dp hairlines`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsGroup(modifier = Modifier.fillMaxWidth().testTag(GROUP)) {
                    repeat(3) { i ->
                        DifftSettingsRow(label = "row $i", modifier = Modifier.testTag(rowTag(i)))
                    }
                }
            }
        }

        assertGap(rowTag(0), rowTag(1))
        assertGap(rowTag(1), rowTag(2))
    }

    // T9
    @Test
    fun `a single-row group adds no hairline`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsGroup(modifier = Modifier.fillMaxWidth().testTag(GROUP)) {
                    DifftSettingsRow(label = "only", modifier = Modifier.testTag(rowTag(0)))
                }
            }
        }

        composeTestRule.onNodeWithTag(GROUP).assertHeightIsEqualTo(DifftSettingsDefaults.RowMinHeight)
    }

    // T10
    @Test
    fun `title and footer render and the title sits 8dp above the card`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsGroup(
                    modifier = Modifier.fillMaxWidth().testTag(GROUP),
                    title = TITLE,
                    footer = { DifftSettingsCaption(FOOTER) },
                ) {
                    DifftSettingsRow(label = "row", modifier = Modifier.testTag(rowTag(0)))
                }
            }
        }

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(FOOTER).assertIsDisplayed()

        val title = composeTestRule.onNodeWithText(TITLE).getBoundsInRoot()
        val row = composeTestRule.onNodeWithTag(rowTag(0)).getBoundsInRoot()
        val gap = row.top.value - title.bottom.value
        assertTrue(
            "expected ${DifftSettingsDefaults.TitleBottomGap} between title and card, measured ${gap}dp",
            abs(gap - DifftSettingsDefaults.TitleBottomGap.value) <= 0.5f,
        )
    }

    // T11
    @Test
    fun `without title or footer the group is exactly its rows plus hairlines`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsGroup(modifier = Modifier.fillMaxWidth().testTag(GROUP)) {
                    repeat(2) { i ->
                        DifftSettingsRow(label = "row $i", modifier = Modifier.testTag(rowTag(i)))
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(GROUP).assertHeightIsEqualTo(
            DifftSettingsDefaults.RowMinHeight * 2 + DifftSettingsDefaults.SeparatorThickness,
        )
    }

    // T12
    @Test
    fun `titleTrailing is clickable and ends at the caption inset`() {
        var clicks = 0
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsGroup(
                    modifier = Modifier.fillMaxWidth().testTag(GROUP),
                    title = TITLE,
                    titleTrailing = {
                        Text(
                            text = ACTION,
                            modifier = Modifier.testTag(ACTION).clickable { clicks++ },
                        )
                    },
                ) {
                    DifftSettingsRow(label = "row", modifier = Modifier.testTag(rowTag(0)))
                }
            }
        }

        composeTestRule.onNodeWithTag(ACTION).performClick()
        assertEquals(1, clicks)

        val group = composeTestRule.onNodeWithTag(GROUP).getBoundsInRoot()
        val action = composeTestRule.onNodeWithTag(ACTION).getBoundsInRoot()
        val inset = group.right.value - action.right.value
        assertTrue(
            "expected ${DifftSettingsDefaults.CaptionInset} end inset, measured ${inset}dp",
            abs(inset - DifftSettingsDefaults.CaptionInset.value) <= 0.5f,
        )
    }

    // T14
    @Test
    fun `switch rows in a group keep the settings row geometry`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsGroup(modifier = Modifier.fillMaxWidth().testTag(GROUP)) {
                    repeat(2) { i ->
                        DifftSwitchRow(
                            label = "switch $i",
                            checked = false,
                            onCheckedChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(rowTag(i))
                                .background(DifftSettingsDefaults.RowSurfaceColor),
                            contentPadding = DifftSettingsDefaults.RowContentPadding,
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(rowTag(0)).assertHeightIsEqualTo(DifftSettingsDefaults.RowMinHeight)
        composeTestRule.onNodeWithTag(rowTag(1)).assertHeightIsEqualTo(DifftSettingsDefaults.RowMinHeight)
        assertGap(rowTag(0), rowTag(1))
    }

    private companion object {
        const val GROUP = "difft_settings_group"
        const val TITLE = "GroupTitleProbe"
        const val FOOTER = "GroupFooterProbe"
        const val ACTION = "GroupActionProbe"
    }
}
