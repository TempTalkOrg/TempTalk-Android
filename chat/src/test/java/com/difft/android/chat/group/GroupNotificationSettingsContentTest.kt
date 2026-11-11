package com.difft.android.chat.group

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import androidx.test.core.app.ApplicationProvider
import android.content.Context





@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GroupNotificationSettingsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int) = context.getString(resId)

    
    @Test
    fun `following the global setting shows the footer and hides the options`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                GroupNotificationSettingsContent(
                    globalNotificationType = GlobalNotificationType.ALL.value,
                    isDefaultEnabled = true,
                    currentType = GlobalNotificationType.ALL.value,
                    onDefaultSettingClick = {},
                    onSwitchChanged = {},
                    onOptionSelected = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.notification_default_setting), substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.notification_change_default_setting_tip))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.notification_update_default_group_setting))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.notification_custom_settings))
            .assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.notification_off)).assertDoesNotExist()
    }

    
    @Test
    fun `with the global setting off the options render and report their value`() {
        var selected = -1
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                GroupNotificationSettingsContent(
                    globalNotificationType = GlobalNotificationType.ALL.value,
                    isDefaultEnabled = false,
                    currentType = GlobalNotificationType.MENTION.value,
                    onDefaultSettingClick = {},
                    onSwitchChanged = {},
                    onOptionSelected = { selected = it },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.notification_custom_settings)).assertIsDisplayed()
        
        composeTestRule.onNodeWithText(string(R.string.notification_mention_only_description))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.notification_off)).assertIsDisplayed()

        
        composeTestRule.onAllNodesWithText(string(R.string.notification_all)).onLast().performClick()
        assertEquals(GlobalNotificationType.ALL.value, selected)
    }
}
