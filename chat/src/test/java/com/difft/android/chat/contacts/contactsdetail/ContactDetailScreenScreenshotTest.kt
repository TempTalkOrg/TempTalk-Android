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
 * Screenshot baselines for [ContactDetailScreen]: weak-pending, official-account (P1-04), and
 * normal-friend states.
 *
 * A weak-pending contact renders as a non-friend card (isFriend=false → "Add Contact" entry)
 * plus an extra "Remove Now" action tinted with the destructive [DifftTheme] error colour.
 *
 * The official-account variant (`isOfficialAccount = true`) shows the official badge next to the
 * name, the "Official Account" subtitle label, and a website info row; the call action is hidden.
 * The normal-friend variant (`isOfficialAccount = false`, isFriend=true) shows message + call +
 * share actions and no badge — this is the P1-04 param-consolidation counterpart of the official
 * state (both derive from the single `isOfficialAccount` flag after §8).
 *
 * Design source: NONE — no Figma supplied for this feature. Baselines lock the current shipped
 * [ContactDetailScreen] rendering (unchanged visually by the §8 param rename), not a Figma ground
 * truth. The "Remove Now" button reuses the existing ActionButton style (72dp tile, bgElevated,
 * iconMedium icon, labelMedium label) with `DifftTheme.colors.error` tint.
 *
 * Light + dark parity: every content variant (weak-pending, official, normal-friend) is captured
 * in both themes.
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
        isOfficialAccount = false,
    )

    private val officialAccountState = ContactDetailUiState(
        displayName = "Support Team",
        userId = "+10000",
        joinedAt = "2024-01-15",
        commonGroupsCount = 0,
        isFriend = true,
        isSelf = false,
        isOfficialAccount = true,
        website = "https://example.com/support",
    )

    private val normalFriendState = ContactDetailUiState(
        displayName = "Jane Smith",
        userId = "jane_smith",
        joinedAt = "2024-02-10",
        commonGroupsCount = 3,
        isFriend = true,
        isSelf = false,
        isOfficialAccount = false,
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

    /** Renders [ContactDetailScreen] with all callbacks no-op'd and captures it to [fileName]. */
    private fun captureContactDetailScreen(uiState: ContactDetailUiState, darkTheme: Boolean, fileName: String) {
        composeTestRule.setContent {
            DifftTheme(darkTheme = darkTheme) {
                ContactDetailScreen(
                    uiState = uiState,
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
        composeTestRule.onRoot().captureRoboImage(fileName)
    }

    @Test
    fun `contact detail official account light`() {
        captureContactDetailScreen(officialAccountState, darkTheme = false, "screenshots/chat/ContactDetailScreen_official_light.png")
    }

    @Test
    fun `contact detail official account dark`() {
        captureContactDetailScreen(officialAccountState, darkTheme = true, "screenshots/chat/ContactDetailScreen_official_dark.png")
    }

    @Test
    fun `contact detail normal friend light`() {
        captureContactDetailScreen(normalFriendState, darkTheme = false, "screenshots/chat/ContactDetailScreen_normalFriend_light.png")
    }

    @Test
    fun `contact detail normal friend dark`() {
        captureContactDetailScreen(normalFriendState, darkTheme = true, "screenshots/chat/ContactDetailScreen_normalFriend_dark.png")
    }
}
