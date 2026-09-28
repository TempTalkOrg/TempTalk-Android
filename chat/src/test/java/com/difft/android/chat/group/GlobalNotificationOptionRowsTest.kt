package com.difft.android.chat.group

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.chat.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [GlobalNotificationOptionRows] contract (issue #1223): the single owner of the three
 * [GlobalNotificationType] rows, shared by the global page and the per-group page. MENTION is the
 * only option with an explanation line, and exactly one row carries the checkmark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GlobalNotificationOptionRowsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int) = context.getString(resId)

    // T27
    @Test
    fun `all three options render and report their raw value`() {
        var selected = -1
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DifftSettingsGroup {
                    GlobalNotificationOptionRows(GlobalNotificationType.MENTION.value) { selected = it }
                }
            }
        }

        composeTestRule.onNodeWithText(string(R.string.notification_all), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.notification_mention_only), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.notification_off), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.notification_mention_only_description), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onAllNodesWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true)
            .assertCountEquals(1)

        composeTestRule.onNodeWithText(string(R.string.notification_all), useUnmergedTree = true)
            .performClick()
        assertEquals(GlobalNotificationType.ALL.value, selected)
    }
}
