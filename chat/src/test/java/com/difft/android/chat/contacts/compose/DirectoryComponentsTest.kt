package com.difft.android.chat.contacts.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import org.difft.app.database.models.GroupModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en")
class DirectoryComponentsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `card actions retain disabled state and reject taps`() {
        var messages = 0
        var invitations = 0
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                CardActions(listOf(
                    CardAction("Message", R.drawable.chat_ic_hourglass) { messages++ },
                    CardAction("Invite", R.drawable.chat_ic_hourglass, enabled = false) { invitations++ },
                ))
            }
        }
        rule.onNodeWithText("Invite").assertIsNotEnabled().performClick()
        rule.onNodeWithText("Message").performClick()
        assertEquals(1, messages)
        assertEquals(0, invitations)
        rule.onNodeWithText("Invite").assertIsNotEnabled()
    }

    @Test
    fun `tab selection remains controlled by caller state`() {
        val selected = mutableStateOf(DirectoryTab.Groups)
        rule.setContent {
            DifftTheme(darkTheme = true, applyWindowBackground = false) {
                DirectoryTabBand(selected.value, { selected.value = it })
            }
        }
        rule.onNodeWithText("Groups").assertIsSelected()
        rule.onNodeWithText("Contacts").performClick().assertIsSelected()
        assertEquals(DirectoryTab.Contacts, selected.value)
    }

    @Test
    fun `tiles show localized deadline and hide missing deadline`() {
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                Column {
                    CountdownAvatarTile(
                        avatar = { modifier -> Box(modifier.background(DifftTheme.colors.info)) },
                        name = "Pending", days = 0, todayLabel = "Today",
                    )
                    CountdownAvatarTile(
                        avatar = { modifier -> Box(modifier.background(DifftTheme.colors.info)) },
                        name = "Current", days = null, todayLabel = "Unused today",
                    )
                }
            }
        }
        rule.onNodeWithText("Today").assertExists()
        rule.onNodeWithText("Unused today").assertDoesNotExist()
    }

    @Test
    fun `group row exposes expiry and full membership at the specified height`() {
        val group = GroupModel().apply { gid = "group-id"; messageExpiry = 86_400 }
        var opened = 0
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                DirectoryRow(group, name = "Group", memberCount = 100, isLastInSection = true, onClick = { opened++ })
            }
        }
        rule.onNodeWithText("Group").assertHeightIsEqualTo(72.dp).performClick()
        rule.onNodeWithText("100", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("1d", useUnmergedTree = true).assertExists()
        assertEquals(1, opened)
    }

    @Test
    fun `group avatar hides zero membership and renders supplied membership`() {
        val count = mutableStateOf(0)
        val group = GroupModel().apply { gid = "group-id" }
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                GroupAvatarWithCount(group, memberCount = count.value)
            }
        }
        rule.onNodeWithText("0").assertDoesNotExist()
        rule.runOnIdle { count.value = 2 }
        rule.onNodeWithText("2").assertExists()
        rule.runOnIdle { count.value = 100 }
        rule.onNodeWithText("100").assertExists()
    }

    @Test
    fun `card info row dispatches linked value and banner remains readable`() {
        var opened = 0
        rule.setContent {
            DifftTheme(darkTheme = true, applyWindowBackground = false) {
                Column {
                    CardBanner("3 days left", "Send a message to keep this group")
                    CardInfoRow("Members", "29") { opened++ }
                }
            }
        }
        rule.onNodeWithText("29").performClick()
        assertEquals(1, opened)
        rule.onNodeWithText("3 days left").assertExists()
        rule.onNodeWithText("Send a message to keep this group").assertExists()
    }
}
