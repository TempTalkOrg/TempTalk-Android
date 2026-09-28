package com.difft.android.setting

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.NotificationContentDisplayType
import com.difft.android.chat.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [NotificationContentDisplaySettingsContent] contract (issue #1223): every tap reports through
 * the callback, including a tap on the already-selected row — the host closes the screen on any
 * tap, so suppressing the unchanged case would strand the user on the page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NotificationContentDisplaySettingsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int) = context.getString(resId)

    // T28
    @Test
    fun `tapping the already-selected row still reports it`() {
        val reported = mutableListOf<Int>()
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                NotificationContentDisplaySettingsContent(
                    selected = NotificationContentDisplayType.NAME_AND_CONTENT.value,
                    onSelect = { reported += it },
                )
            }
        }

        composeTestRule
            .onAllNodesWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true)
            .assertCountEquals(1)
        composeTestRule
            .onNodeWithText(string(R.string.notification_display_name_and_content), useUnmergedTree = true)
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText(string(R.string.notification_display_name_and_content), useUnmergedTree = true)
            .performClick()
        assertEquals(listOf(NotificationContentDisplayType.NAME_AND_CONTENT.value), reported)
    }

    // T28
    @Test
    fun `every option renders and an unselected tap reports its value`() {
        val reported = mutableListOf<Int>()
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                NotificationContentDisplaySettingsContent(
                    selected = NotificationContentDisplayType.NAME_AND_CONTENT.value,
                    onSelect = { reported += it },
                )
            }
        }

        composeTestRule
            .onNodeWithText(string(R.string.notification_only_name), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.notification_no_name_or_content), useUnmergedTree = true)
            .performClick()
        assertEquals(listOf(NotificationContentDisplayType.NO_NAME_OR_CONTENT.value), reported)
    }
}
