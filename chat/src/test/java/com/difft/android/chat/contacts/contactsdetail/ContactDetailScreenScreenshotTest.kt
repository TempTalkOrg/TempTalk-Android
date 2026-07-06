package com.difft.android.chat.contacts.contactsdetail

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.difft.android.base.ui.theme.DifftTheme
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot baselines for [ContactDetailScreen] in the weak-pending (delayed-removal) state.
 *
 * A weak-pending contact renders as a non-friend card (isFriend=false → "Add Contact" entry)
 * plus an extra "Remove Now" action tinted with the destructive [DifftTheme] error colour.
 *
 * Design source: NONE — no Figma supplied for this feature. The "Remove Now" button reuses the
 * existing [ContactDetailScreen] ActionButton style (72dp tile, bgElevated, iconMedium icon,
 * labelMedium label) with `DifftTheme.colors.error` for icon+label tint; layout/spacing inherited
 * verbatim from the friend/non-friend variants. The exact styling is still pending UI review —
 * these baselines lock the current implementation, not a Figma ground truth.
 *
 * Light + dark parity: the weak-pending variant is captured in both themes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ContactDetailScreenScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        composeRule = composeTestRule,
        captureRoot = composeTestRule.onRoot(),
        options = RoborazziRule.Options(captureType = RoborazziRule.CaptureType.None),
    )

    private val weakPendingState = ContactDetailUiState(
        displayName = "Removing Soon",
        userId = "removing_id",
        joinedAt = "2024-03-20",
        commonGroupsCount = 0,
        isFriend = false,
        isWeakPending = true,
        isSelf = false,
        isBot = false,
    )

    @Test
    fun `contact detail weak pending light`() {
        composeTestRule.setContent {
            DifftTheme(darkTheme = false) {
                ContactDetailScreen(
                    uiState = weakPendingState,
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
                    onRemoveNowClick = {},
                )
            }
        }
        composeTestRule.onRoot()
            .captureRoboImage("screenshots/chat/ContactDetailScreen_weakPending_light.png")
    }

    @Test
    fun `contact detail weak pending dark`() {
        composeTestRule.setContent {
            DifftTheme(darkTheme = true) {
                ContactDetailScreen(
                    uiState = weakPendingState,
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
                    onRemoveNowClick = {},
                )
            }
        }
        composeTestRule.onRoot()
            .captureRoboImage("screenshots/chat/ContactDetailScreen_weakPending_dark.png")
    }
}
