package com.difft.android.chat.group

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.group.GroupMemberListContract.Member
import com.difft.android.chat.group.GroupMemberListContract.State
import org.difft.app.database.models.ContactorModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en")
class GroupMemberListScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val members = listOf(
        member("m1", "Zoe", GROUP_ROLE_MEMBER, remark = "Designer"),
        member("a1", "Bob", GROUP_ROLE_ADMIN),
        member("o1", "Yan", GROUP_ROLE_OWNER),
        member("m2", "Amy", GROUP_ROLE_MEMBER),
    ).sortedForMemberList()

    @Test fun `owner then admins lead and each section sorts by name`() {
        assertEquals(listOf("o1", "a1", "m2", "m1"), members.map { it.id })
    }

    @Test fun `search matches display name raw names and id ignoring case`() {
        assertEquals(listOf("m1"), members.matching("designer").map { it.id })
        assertEquals(listOf("a1"), members.matching("A1").map { it.id })
        assertEquals(members, members.matching("  "))
    }

    @Test fun `every hit of the query is highlighted`() {
        val text = highlighted("Anna Banana", "an", Color.Red)
        assertEquals(listOf(0 to 2, 6 to 8, 8 to 10), text.spanStyles.map { it.start to it.end })
    }

    @Test fun `the full list opens in sections and the search filters in place with hit counts`() {
        val state = mutableStateOf(State(groupId = "g", loading = false, members = members))
        val opened = mutableListOf<String>()
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                GroupMemberListScreen(state.value, { state.value = state.value.copy(query = it) }, { opened += it }, {},
                    Modifier.size(400.dp, 700.dp))
            }
        }
        compose.onNodeWithText(context.getString(R.string.group_member_list_title)).assertIsDisplayed()
        compose.onNodeWithText("Moderators · 2").assertIsDisplayed()
        compose.onNodeWithText("Participants · 2").assertIsDisplayed()
        compose.onNodeWithText("Zoe").assertIsDisplayed()

        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(GROUP_MEMBER_SEARCH_TAG))).performTextInput("zo")
        compose.onNodeWithText("Participants · 1").assertIsDisplayed()
        compose.onNodeWithText("Moderators", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Zoe").performClick()
        assertEquals(listOf("m1"), opened)

        compose.runOnIdle { state.value = state.value.copy(query = "nobody") }
        compose.onNodeWithText(context.getString(R.string.group_member_list_no_match, "nobody")).assertIsDisplayed()
    }

    @Test fun `the search bar is the global 48dp pill and a tap anywhere on it focuses search instead of a row`() {
        val many = (0..30).map { member("m$it", "Member $it", GROUP_ROLE_MEMBER) }
        val opened = mutableListOf<String>()
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                GroupMemberListScreen(State(groupId = "g", loading = false, members = many), {}, { opened += it }, {},
                    Modifier.size(400.dp, 700.dp))
            }
        }
        val bar = compose.onNodeWithTag(GROUP_MEMBER_SEARCH_TAG).assertHeightIsEqualTo(48.dp)
        
        
        bar.performTouchInput { click(Offset(width - 24f, height / 2f)) }
        bar.performTouchInput { click(Offset(width / 2f, 4f)) }
        assertEquals(emptyList(), opened)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(GROUP_MEMBER_SEARCH_TAG))).assertIsFocused()
    }

    private fun member(id: String, name: String, role: Int, remark: String? = null) =
        Member(id, name, ContactorModel().apply { this.id = id; this.name = name }, role, listOfNotNull(remark))
}
