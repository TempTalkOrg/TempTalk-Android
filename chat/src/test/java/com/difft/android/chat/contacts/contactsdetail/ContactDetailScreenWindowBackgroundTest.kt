package com.difft.android.chat.contacts.contactsdetail

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.createLightExtendedColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals















@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ContactDetailScreenWindowBackgroundTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun fakeUiState() = ContactDetailUiState(
        displayName = "Test User",
        userId = "test_id",
        isFriend = true,
    )

    @Test
    fun `M9 popup mode never writes the host window background`() {
        val sentinel = ColorDrawable(Color.RED)
        composeTestRule.activity.window.setBackgroundDrawable(sentinel)

        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                ContactDetailScreen(
                    uiState = fakeUiState(),
                    isPopupMode = true,
                    onCloseClick = {},
                    onMoreClick = {},
                    onAvatarClick = {},
                    onOriginalAvatarClick = {},
                    onEditClick = {},
                    onMessageClick = {},
                    onCallClick = {},
                    onShareClick = {},
                    onAddFriendClick = {},
                    onCommonGroupsClick = {},
                    onCopyUserId = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        val background = composeTestRule.activity.window.decorView.background as ColorDrawable
        assertEquals(
            Color.RED,
            background.color,
            "isPopupMode = true (BottomSheet path) must resolve to applyWindowBackground = false " +
                "and never touch the host window background",
        )
    }

    @Test
    fun `M10 non-popup mode keeps writing the host window background unaffected`() {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = true) {
                ContactDetailScreen(
                    uiState = fakeUiState(),
                    isPopupMode = false,
                    onCloseClick = {},
                    onMoreClick = {},
                    onAvatarClick = {},
                    onOriginalAvatarClick = {},
                    onEditClick = {},
                    onMessageClick = {},
                    onCallClick = {},
                    onShareClick = {},
                    onAddFriendClick = {},
                    onCommonGroupsClick = {},
                    onCopyUserId = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        val background = composeTestRule.activity.window.decorView.background as ColorDrawable
        assertEquals(
            createLightExtendedColors().bg.toArgb(),
            background.color,
            "isPopupMode = false (ContactDetailActivity path 1 / IndexActivity dual-pane path 3) " +
                "must keep resolving to applyWindowBackground = true, unaffected by this migration",
        )
    }
}
