package com.difft.android.chat.setting

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ChatArchiveSettingsContent] contract (issue #1223): a stored value the server configuration no
 * longer offers is appended as the last row, and a tap reports the raw seconds value.
 *
 * The row labels go through `Long.toArchiveTimeDisplayText()` -> `TimeUtils.millis2FitTimeSpan` ->
 * `ResUtils.getString`, which needs an initialised `ApplicationHelper.instance`, hence
 * [TestScopeApplication].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34])
class ChatArchiveSettingsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var selected: Long? = null

    private fun setContent(options: List<Long>, current: Long) {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                ChatArchiveSettingsContent(
                    options = options,
                    selected = current,
                    onSelect = { selected = it },
                )
            }
        }
    }

    // T22
    @Test
    fun `a stored value outside the configured list is appended as the last row`() {
        setContent(options = listOf(NEVER, ONE_DAY), current = ONE_HOUR)

        val never = label(NEVER)
        val day = label(ONE_DAY)
        val hour = label(ONE_HOUR)
        assertTrue("expected $day below $never", day > never)
        assertTrue("expected the appended $hour row last", hour > day)
    }

    // T23
    @Test
    fun `a stored value already in the list is not duplicated`() {
        setContent(options = listOf(NEVER, ONE_DAY), current = ONE_DAY)

        composeTestRule
            .onNodeWithText(ONE_HOUR.toArchiveTimeDisplayText(), useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule
            .onAllNodesWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true)
            .assertCountEquals(1)
    }

    // T24
    @Test
    fun `tapping an unselected row reports its raw value`() {
        setContent(options = listOf(NEVER, ONE_DAY), current = NEVER)

        composeTestRule
            .onNodeWithText(ONE_DAY.toArchiveTimeDisplayText(), useUnmergedTree = true)
            .performClick()
        assertEquals(ONE_DAY, selected)
    }

    private fun label(seconds: Long): Float = composeTestRule
        .onNodeWithText(seconds.toArchiveTimeDisplayText(), useUnmergedTree = true)
        .getBoundsInRoot()
        .top
        .value

    private companion object {
        const val NEVER = 0L
        const val ONE_HOUR = 3600L
        const val ONE_DAY = 86_400L
    }
}
