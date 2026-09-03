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

/**
 * M9/M10 (issue #1127, family E): [ContactDetailFragment] gates `applyWindowBackground` on
 * `!isPopupMode`. Mounts the real [ContactDetailScreen] composable directly on a bare
 * [ComponentActivity] (not the real `@AndroidEntryPoint` `ContactDetailFragment` — this codebase
 * has no Hilt-Robolectric harness for constructing Hilt fragments directly in unit tests) with the
 * exact literal `DifftTheme` call shape `ContactDetailFragment.onCreateView` uses post-migration.
 *
 * M9 pins the popup half (`isPopupMode = true` -> `applyWindowBackground = false`): the
 * `ContactDetailBottomSheetDialogFragment` mount path must stop writing the window background.
 * M10 pins the non-popup half (`isPopupMode = false` -> `applyWindowBackground = true`), which
 * covers BOTH `ContactDetailActivity` (path 1) and `IndexActivity`'s dual-pane detail (path 3) —
 * mechanically identical from `DifftTheme`'s perspective — and must keep writing,
 * unaffected by this migration.
 */
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
