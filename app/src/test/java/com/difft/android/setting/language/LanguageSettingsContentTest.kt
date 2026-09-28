package com.difft.android.setting.language

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.LanguageData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.math.abs

/**
 * [LanguageSettingsContent] contract (issue #1223): the rows render in the order given, the
 * checkmark follows [LanguageData.selected] alone, and a tap reports the entry object itself so the
 * host decides whether a restart is needed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LanguageSettingsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(
        languages: List<LanguageData>,
        reported: MutableList<LanguageData>,
    ) {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                LanguageSettingsContent(
                    languages = languages,
                    onSelect = { reported += it },
                )
            }
        }
    }

    private fun label(text: String): SemanticsNodeInteraction =
        composeTestRule.onNodeWithText(text, useUnmergedTree = true)

    // P5
    @Test
    fun `both languages render in order with the checkmark on the selected entry`() {
        setContent(listOf(english(selected = true), chinese()), mutableListOf())

        label(ENGLISH).assertIsDisplayed()
        label(CHINESE).assertIsDisplayed()

        val englishTop = label(ENGLISH).getBoundsInRoot().top.value
        val chineseTop = label(CHINESE).getBoundsInRoot().top.value
        assertTrue(
            "expected English above 中文, measured $englishTop / $chineseTop",
            englishTop < chineseTop,
        )

        val marks = composeTestRule
            .onAllNodesWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true)
        marks.assertCountEquals(1)
        val mark = marks[0].getBoundsInRoot()
        val row = label(ENGLISH).getBoundsInRoot()
        val markCenter = (mark.top.value + mark.bottom.value) / 2f
        val rowCenter = (row.top.value + row.bottom.value) / 2f
        assertTrue(
            "checkmark centred at ${markCenter}dp, English row centred at ${rowCenter}dp",
            abs(markCenter - rowCenter) < 2f,
        )
    }

    // P6
    @Test
    fun `a tap reports the entry object itself`() {
        val reported = mutableListOf<LanguageData>()
        val chineseEntry = chinese()
        setContent(listOf(english(selected = true), chineseEntry), reported)

        label(CHINESE).performClick()

        assertEquals(1, reported.size)
        assertSame(chineseEntry, reported[0])
    }

    // P7
    @Test
    fun `no entry selected marks no row`() {
        setContent(listOf(english(), chinese()), mutableListOf())

        composeTestRule
            .onAllNodesWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    private fun english(selected: Boolean = false) = LanguageData(ENGLISH, Locale.US, selected)

    private fun chinese(selected: Boolean = false) = LanguageData(CHINESE, Locale.CHINA, selected)

    private companion object {
        const val ENGLISH = "English"
        const val CHINESE = "中文"
    }
}
