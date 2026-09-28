package com.difft.android.setting.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
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
 * [ThemeSettingsContent] contract (issue #1223): three night-mode rows in a fixed order, the
 * checkmark placed by exact equality — so a stored mode outside the three marks no row — and every
 * tap reported, including a tap on the already-selected row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ThemeSettingsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val systemLabel = context.getString(R.string.me_theme_system)
    private val lightLabel = context.getString(R.string.me_theme_light)
    private val darkLabel = context.getString(R.string.me_theme_dark)

    private fun setContent(selectedMode: Int, reported: MutableList<Int>) {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                ThemeSettingsContent(
                    selectedMode = selectedMode,
                    onSelect = { reported += it },
                )
            }
        }
    }

    private fun label(text: String): SemanticsNodeInteraction =
        composeTestRule.onNodeWithText(text, useUnmergedTree = true)

    private fun assertCheckmarkOn(text: String) {
        val marks = composeTestRule
            .onAllNodesWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true)
        marks.assertCountEquals(1)
        val mark = marks[0].getBoundsInRoot()
        val row = label(text).getBoundsInRoot()
        val markCenter = (mark.top.value + mark.bottom.value) / 2f
        val rowCenter = (row.top.value + row.bottom.value) / 2f
        assertTrue(
            "checkmark centred at ${markCenter}dp, \"$text\" row centred at ${rowCenter}dp",
            abs(markCenter - rowCenter) < 2f,
        )
    }

    // P1
    @Test
    fun `every mode renders in order with the checkmark on the stored one`() {
        setContent(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, mutableListOf())

        label(systemLabel).assertIsDisplayed()
        label(lightLabel).assertIsDisplayed()
        label(darkLabel).assertIsDisplayed()

        val systemTop = label(systemLabel).getBoundsInRoot().top.value
        val lightTop = label(lightLabel).getBoundsInRoot().top.value
        val darkTop = label(darkLabel).getBoundsInRoot().top.value
        assertTrue(
            "expected System < Light < Dark, measured $systemTop / $lightTop / $darkTop",
            systemTop < lightTop && lightTop < darkTop,
        )

        assertCheckmarkOn(systemLabel)
    }

    // P2
    @Test
    fun `tapping an unselected mode reports its bare mode value`() {
        val reported = mutableListOf<Int>()
        setContent(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, reported)

        label(darkLabel).performClick()

        assertEquals(listOf(AppCompatDelegate.MODE_NIGHT_YES), reported)
    }

    // P3
    @Test
    fun `tapping the already-selected mode still reports it`() {
        val reported = mutableListOf<Int>()
        setContent(AppCompatDelegate.MODE_NIGHT_NO, reported)

        label(lightLabel).performClick()

        assertEquals(listOf(AppCompatDelegate.MODE_NIGHT_NO), reported)
    }

    // P4
    @Test
    fun `a stored mode outside the three marks no row`() {
        setContent(UNKNOWN_MODE, mutableListOf())

        composeTestRule
            .onAllNodesWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true)
            .assertCountEquals(0)
        label(systemLabel).assertIsDisplayed()
        label(lightLabel).assertIsDisplayed()
        label(darkLabel).assertIsDisplayed()
    }

    private companion object {
        /** Not one of the three AppCompatDelegate night modes. */
        const val UNKNOWN_MODE = 99
    }
}
