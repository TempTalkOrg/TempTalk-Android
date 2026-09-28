package com.difft.android.setting

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.difft.android.R
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.login.PasscodeUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import util.TimeUtils
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/**
 * [PasscodeTimeoutContent] and [passcodeTimeoutLabel] contract (issue #1223): zero reads as the
 * instant string and every other value as a duration, the six rows keep their stored order, a null
 * stored value marks no row, and every tap is reported — the page does not close on selection.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PasscodeTimeoutContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setContent(selected: Int?, reported: MutableList<Int>) {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                PasscodeTimeoutContent(
                    selected = selected,
                    onSelect = { reported += it },
                )
            }
        }
    }

    private fun label(text: String): SemanticsNodeInteraction =
        composeTestRule.onNodeWithText(text, useUnmergedTree = true)

    private fun duration(seconds: Int) =
        TimeUtils.millis2FitTimeSpan(seconds.seconds.inWholeMilliseconds, 3, false)

    // P8
    @Test
    fun `zero reads as the instant string and other values as durations`() {
        assertEquals(
            context.getString(R.string.settings_screen_lock_timeout_instant),
            passcodeTimeoutLabel(context, 0),
        )
        assertEquals(duration(60), passcodeTimeoutLabel(context, 60))
        assertEquals(duration(300), passcodeTimeoutLabel(context, 300))
        assertEquals(duration(3600), passcodeTimeoutLabel(context, 3600))
    }

    // P9
    @Test
    fun `all timeouts render in order and a null stored value marks no row`() {
        setContent(null, mutableListOf())

        val tops = PasscodeUtil.TIMEOUT_LIST.map { seconds ->
            val text = passcodeTimeoutLabel(context, seconds)
            label(text).assertIsDisplayed()
            label(text).getBoundsInRoot().top.value
        }
        assertTrue(
            "expected strictly increasing row tops, measured $tops",
            tops.zipWithNext().all { (upper, lower) -> upper < lower },
        )

        composeTestRule
            .onAllNodesWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true)
            .assertCountEquals(0)
    }

    // P10
    @Test
    fun `the checkmark sits on the stored timeout`() {
        setContent(PasscodeUtil.DEFAULT_TIMEOUT, mutableListOf())

        val marks = composeTestRule
            .onAllNodesWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true)
        marks.assertCountEquals(1)
        val mark = marks[0].getBoundsInRoot()
        val row = label(passcodeTimeoutLabel(context, PasscodeUtil.DEFAULT_TIMEOUT))
            .getBoundsInRoot()
        val markCenter = (mark.top.value + mark.bottom.value) / 2f
        val rowCenter = (row.top.value + row.bottom.value) / 2f
        assertTrue(
            "checkmark centred at ${markCenter}dp, stored row centred at ${rowCenter}dp",
            abs(markCenter - rowCenter) < 2f,
        )
    }

    // P11
    @Test
    fun `tapping the instant row reports zero seconds`() {
        val reported = mutableListOf<Int>()
        setContent(PasscodeUtil.DEFAULT_TIMEOUT, reported)

        label(passcodeTimeoutLabel(context, 0)).performClick()

        assertEquals(listOf(0), reported)
    }

    // P12
    @Test
    fun `tapping the already-selected timeout still reports it`() {
        val reported = mutableListOf<Int>()
        setContent(PasscodeUtil.DEFAULT_TIMEOUT, reported)

        label(passcodeTimeoutLabel(context, PasscodeUtil.DEFAULT_TIMEOUT)).performClick()

        assertEquals(listOf(PasscodeUtil.DEFAULT_TIMEOUT), reported)
    }
}
