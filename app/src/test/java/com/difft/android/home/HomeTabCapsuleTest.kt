package com.difft.android.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import org.difft.app.database.models.ContactorModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Contract tests for the floating home tab capsule: selection, badge, Me avatar fallback, taps, search. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class HomeTabCapsuleTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(HomeTabCapsuleState())

    /** Sets the content once; later cases of the same test update [state] and recompose. */
    private fun show(
        initial: HomeTabCapsuleState,
        onTabClick: (Int) -> Unit = {},
        onChatsDoubleTap: () -> Unit = {},
        onSearch: () -> Unit = {},
        hostWidth: androidx.compose.ui.unit.Dp? = null,
    ) {
        state = initial
        composeTestRule.setContent {
            DifftTheme {
                val host = if (hostWidth != null) Modifier.width(hostWidth) else Modifier
                Box(host) {
                    HomeTabCapsule(
                        state = state,
                        onTabClick = onTabClick,
                        onChatsDoubleTap = onChatsDoubleTap,
                        onSearch = onSearch,
                    )
                }
            }
        }
    }

    private fun update(next: HomeTabCapsuleState) {
        state = next
        composeTestRule.waitForIdle()
    }

    @Test
    fun `exactly the selected slot reports selected and every slot keeps a 48dp touch target`() {
        show(HomeTabCapsuleState(selected = HomeTab.CONTACTS))
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CHATS)).assertIsNotSelected().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CONTACTS)).assertIsSelected().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithTag(slotTag(HomeTab.ME)).assertIsNotSelected().assertHeightIsAtLeast(48.dp)

        update(HomeTabCapsuleState(selected = HomeTab.ME))
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CONTACTS)).assertIsNotSelected()
        composeTestRule.onNodeWithTag(slotTag(HomeTab.ME)).assertIsSelected()
    }

    @Test
    fun `chats badge shows the count, caps at 99+, or a dot for a manual unread`() {
        show(HomeTabCapsuleState(chatsBadge = ChatsBadge.Count(7, muted = false)))
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_BADGE).assertExists().assertTextEquals("7")

        update(HomeTabCapsuleState(chatsBadge = ChatsBadge.Count(120, muted = true)))
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_BADGE).assertTextEquals("99+")

        update(HomeTabCapsuleState(chatsBadge = ChatsBadge.Dot(muted = false)))
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_BADGE_DOT).assertExists()
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_BADGE).assertDoesNotExist()

        update(HomeTabCapsuleState())
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_BADGE).assertDoesNotExist()
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_BADGE_DOT).assertDoesNotExist()
    }

    @Test
    fun `me slot falls back to the icon until a usable own contact is known, then shows the avatar`() {
        show(HomeTabCapsuleState(me = null))
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_ME_ICON).assertExists()
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_ME_AVATAR).assertDoesNotExist()

        // A contact record with neither a picture nor a name stays on the icon (no ID-letter avatar).
        update(HomeTabCapsuleState(me = ContactorModel().apply { id = "+10001" }))
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_ME_ICON).assertExists()
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_ME_AVATAR).assertDoesNotExist()

        update(HomeTabCapsuleState(me = ContactorModel().apply { id = "+10001"; name = "Nate" }))
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_ME_AVATAR).assertExists()
        composeTestRule.onNodeWithTag(useUnmergedTree = true, testTag = TAG_ME_ICON).assertDoesNotExist()
    }

    @Test
    fun `a tap selects its tab and two quick chats taps also fire the double-tap`() {
        val clicks = mutableListOf<Int>()
        var doubleTaps = 0
        show(HomeTabCapsuleState(), onTabClick = { clicks += it }, onChatsDoubleTap = { doubleTaps++ })

        composeTestRule.onNodeWithTag(slotTag(HomeTab.ME)).performClick()
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CHATS)).performClick()
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CHATS)).performClick()

        assertEquals(listOf(HomeTab.ME, HomeTab.CHATS, HomeTab.CHATS), clicks)
        assertEquals("second Chats tap within the timeout is the double-tap", 1, doubleTaps)
    }

    @Test
    fun `the search circle is a 62dp button that opens search`() {
        var searches = 0
        show(HomeTabCapsuleState(), onSearch = { searches++ })

        composeTestRule.onNodeWithTag(TAG_SEARCH)
            .assertWidthIsEqualTo(62.dp)
            .assertHeightIsEqualTo(62.dp)
            .performClick()

        assertEquals(1, searches)
    }

    @Test
    @Config(sdk = [33], qualifiers = "w480dp-h800dp")
    fun `a wide host gets the full 274dp capsule`() {
        // Full width: capsule 274 + 12 + 62 = 348.
        show(HomeTabCapsuleState(), hostWidth = 400.dp)
        composeTestRule.onNodeWithTag(TAG_CAPSULE).assertWidthIsEqualTo(274.dp)
    }

    @Test
    fun `the narrowest list pane shrinks the capsule while the search circle keeps its size`() {
        // The narrowest list pane (280dp) leaves 248dp inside the 16dp outer padding.
        show(HomeTabCapsuleState(), hostWidth = 280.dp)
        composeTestRule.onNodeWithTag(TAG_CAPSULE).assertWidthIsEqualTo(174.dp)
        composeTestRule.onNodeWithTag(TAG_SEARCH).assertWidthIsEqualTo(62.dp)
        composeTestRule.onNodeWithTag(slotTag(HomeTab.CHATS)).assertHeightIsAtLeast(48.dp)
    }
}
