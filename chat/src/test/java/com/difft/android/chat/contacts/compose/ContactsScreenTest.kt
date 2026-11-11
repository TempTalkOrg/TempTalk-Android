package com.difft.android.chat.contacts.compose

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.contacts.data.ContactsSnapshot
import com.difft.android.chat.contacts.data.PendingContactMetadata
import com.difft.android.chat.contacts.data.assembleContactsState
import com.difft.android.chat.contacts.mvi.ContactsContract.Intent
import com.difft.android.chat.contacts.mvi.ContactsContract.State
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en")
class ContactsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun clearRemarks() { ContactRemarkCache.clear() }

    @Test fun `index letters equal nonempty active sections including hash last`() {
        val state = assembleContactsState(ContactsSnapshot(friends = listOf(
            contact("a", "Alice"), contact("b", "Bob"), contact("z", "张三"), contact("n", "1 team"),
        )), State(), DirectoryTab.Contacts, NOW)
        render(state)
        val letters = compose.onNodeWithTag(CONTACTS_INDEX_TAG).onChildren().fetchSemanticsNodes()
            .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text }
        assertEquals<List<String>>(listOf("A", "B", "Z", "#"), letters)
        assertEquals<List<String>>(state.contactSections.map { it.letter }, letters)
    }

    @Test fun `pending countdown remains a strip card and never duplicates the alphabet directory`() {
        val snapshot = ContactsSnapshot(
            friends = listOf(contact("weak", "Pending"), contact("friend", "Friend")),
            weakContacts = listOf(contact("weak", "Pending")),
            pending = mapOf("weak" to PendingContactMetadata(NOW + 1, NOW)),
        )
        val state = assembleContactsState(snapshot, State(), DirectoryTab.Contacts, NOW)
        val intents = mutableListOf<Intent>()
        render(state, intents::add)
        compose.onNodeWithText("Today").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("Pending").fetchSemanticsNodes().size)
        compose.onNodeWithText("Pending").performClick()
        assertEquals<List<Intent>>(listOf(Intent.OpenContact("weak")), intents)
    }

    @Test fun `group directory action opens group card and empty strips stay absent`() {
        val group = GroupModel().apply { gid = "group"; name = "Project"; status = 0 }
        val state = assembleContactsState(ContactsSnapshot(groups = listOf(group)), State(), DirectoryTab.Groups, NOW)
        val intents = mutableListOf<Intent>()
        render(state, intents::add)
        compose.onNodeWithText(context.getString(R.string.weak_contact_pending_group_title), substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.contacts_expiring_groups), substring = true).assertDoesNotExist()
        compose.onNodeWithText("Project").performClick()
        assertEquals<List<Intent>>(listOf(Intent.OpenGroup("group")), intents)
    }

    @Test fun `index jump to a short final section stops at the list end without a blank tail`() {
        val contacts = (0..25).map { contact("a$it", "Alice $it") } + contact("z", "Zoo")
        val state = assembleContactsState(ContactsSnapshot(friends = contacts), State(), DirectoryTab.Contacts, NOW)
        render(state)
        compose.onNode(hasText("Z") and hasAnyAncestor(hasTestTag(CONTACTS_INDEX_TAG)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        val tabs = compose.onNodeWithTag(CONTACTS_TABS_TAG).fetchSemanticsNode().boundsInRoot
        val section = compose.onNodeWithTag(CONTACTS_SECTION_TAG_PREFIX + "Z").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithText("Zoo").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag(CONTACTS_LIST_TAG).fetchSemanticsNode().boundsInRoot
        
        compose.onNodeWithTag(CONTACTS_STRIPS_TAG).assertIsNotDisplayed()
        assertTrue(section.top >= tabs.bottom - 1f)
        val tail = with(compose.density) { (list.bottom - last.bottom).toDp() }
        assertTrue(tail <= 72.dp + 40.dp, "blank below the last row: $tail")
    }

    @Test fun `sections loading in above the tabs keep the list at its top`() {
        val snapshot = ContactsSnapshot(friends = (0..30).map { contact("c$it", "Carol $it") })
        val base = assembleContactsState(snapshot, State(), DirectoryTab.Contacts, NOW)
        val state = mutableStateOf(base)
        compose.setContent {
            DifftTheme(applyWindowBackground = false) { ContactsScreen(state.value, {}, Modifier.size(400.dp, 650.dp)) }
        }
        compose.waitForIdle()
        compose.runOnIdle { state.value = assembleContactsState(withStrip(snapshot), base, DirectoryTab.Contacts, NOW) }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.weak_contact_pending_group_title), substring = true).assertIsDisplayed()
        compose.onNodeWithText("Carol 0").assertIsDisplayed()
        val tabs = compose.onNodeWithTag(CONTACTS_TABS_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onNodeWithText("Carol 0").fetchSemanticsNode().boundsInRoot.top >= tabs.bottom - 1f)
    }

    @Test fun `a swipe moves only the lists while the strips and tabs stay in place`() {
        val group = GroupModel().apply { gid = "group"; name = "Project"; status = 0 }
        val state = assembleContactsState(withStrip(ContactsSnapshot(friends = listOf(contact("friend", "Friend")), groups = listOf(group))),
            State(), DirectoryTab.Groups, NOW)
        render(state)
        val strips = compose.onNodeWithTag(CONTACTS_STRIPS_TAG).fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithText("Project").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        
        compose.onNodeWithTag(CONTACTS_PAGER_TAG).performTouchInput { swipeLeft(startX = centerX, endX = centerX - 150f) }
        compose.mainClock.advanceTimeByFrame()
        val moved = compose.onNodeWithText("Project").fetchSemanticsNode().boundsInRoot
        assertTrue(moved.right < row.right - 50f, "row $row -> $moved")
        assertEquals(strips, compose.onNodeWithTag(CONTACTS_STRIPS_TAG).fetchSemanticsNode().boundsInRoot)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun `scrolling up a short list still collapses the strips until the tabs pin under the title`() {
        val state = assembleContactsState(withStrip(ContactsSnapshot(friends = listOf(contact("friend", "Friend")))), State(),
            DirectoryTab.Contacts, NOW)
        render(state)
        val title = compose.onNodeWithTag(CONTACTS_TITLE_ROW_TAG).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(CONTACTS_LIST_TAG).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag(CONTACTS_STRIPS_TAG).assertIsNotDisplayed()
        assertEquals(title.bottom, compose.onNodeWithTag(CONTACTS_TABS_TAG).fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun `a short window keeps every strip at its natural height`() {
        val weak = contact("weak", "Pending")
        val group = GroupModel().apply { gid = "g"; name = "Project"; status = 0; expireTime = NOW + 3 * 86_400_000L }
        val state = assembleContactsState(ContactsSnapshot(friends = listOf(weak), weakContacts = listOf(weak),
            pending = mapOf("weak" to PendingContactMetadata(NOW + 1, NOW)), groups = listOf(group)), State(), DirectoryTab.Contacts, NOW)
        val height = mutableStateOf(900.dp)
        compose.setContent {
            DifftTheme(applyWindowBackground = false) { ContactsScreen(state, {}, Modifier.size(400.dp, height.value)) }
        }
        compose.waitForIdle()
        val natural = compose.onNodeWithTag(CONTACTS_STRIPS_TAG).fetchSemanticsNode().size.height
        assertTrue(natural > with(compose.density) { 160.dp.roundToPx() }, "strips $natural px fit the short window")
        compose.runOnIdle { height.value = 160.dp }
        compose.waitForIdle()
        assertEquals(natural, compose.onNodeWithTag(CONTACTS_STRIPS_TAG).fetchSemanticsNode().size.height)
    }

    @Test fun `dragging the strips moves the header too`() {
        val contacts = (0..30).map { contact("c$it", "Carol $it") }
        val state = assembleContactsState(withStrip(ContactsSnapshot(friends = contacts)), State(), DirectoryTab.Contacts, NOW)
        render(state)
        compose.onNodeWithTag(CONTACTS_STRIPS_TAG).performTouchInput { swipeUp(startY = bottom - 1f, endY = top - 600f) }
        compose.waitForIdle()
        compose.onNodeWithTag(CONTACTS_STRIPS_TAG).assertIsNotDisplayed()
    }

    @Test fun `pulling down expands the header first and refreshes only at the top`() {
        val contacts = (0..30).map { contact("c$it", "Carol $it") }
        val state = assembleContactsState(withStrip(ContactsSnapshot(friends = contacts)), State(), DirectoryTab.Contacts, NOW)
        val intents = mutableListOf<Intent>()
        render(state, intents::add)
        compose.onNodeWithTag(CONTACTS_LIST_TAG).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag(CONTACTS_STRIPS_TAG).assertIsNotDisplayed()
        
        compose.onNodeWithTag(CONTACTS_LIST_TAG).performTouchInput { swipeDown(startY = top + 1f, endY = top + 120f) }
        compose.waitForIdle()
        assertTrue(Intent.PullRefresh !in intents)
        compose.onNodeWithTag(CONTACTS_LIST_TAG).performTouchInput { swipeDown() }
        compose.onNodeWithTag(CONTACTS_LIST_TAG).performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithTag(CONTACTS_STRIPS_TAG).assertIsDisplayed()
        assertTrue(Intent.PullRefresh in intents)
    }

    @Test fun `swiping the directory switches between groups and contacts`() {
        val group = GroupModel().apply { gid = "group"; name = "Project"; status = 0 }
        val state = mutableStateOf(assembleContactsState(ContactsSnapshot(friends = listOf(contact("friend", "Friend")),
            groups = listOf(group)), State(), DirectoryTab.Groups, NOW))
        val intents = mutableListOf<Intent>()
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                ContactsScreen(state.value, { intents += it; if (it is Intent.SelectTab) state.value = state.value.copy(selectedTab = it.tab, hasUserSelectedTab = true) },
                    Modifier.size(400.dp, 650.dp))
            }
        }
        compose.onNodeWithTag(CONTACTS_PAGER_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue(Intent.SelectTab(DirectoryTab.Contacts) in intents)
        compose.onNodeWithText("Friend").assertIsDisplayed()
    }

    @Test fun `a tab chosen while a swipe springs back is applied once the pager stops`() {
        val group = GroupModel().apply { gid = "group"; name = "Project"; status = 0 }
        val state = mutableStateOf(assembleContactsState(ContactsSnapshot(friends = listOf(contact("friend", "Friend")),
            groups = listOf(group)), State(), DirectoryTab.Groups, NOW))
        compose.setContent {
            DifftTheme(applyWindowBackground = false) { ContactsScreen(state.value, {}, Modifier.size(400.dp, 650.dp)) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        
        compose.onNodeWithTag(CONTACTS_PAGER_TAG).performTouchInput { swipeLeft(startX = centerX, endX = centerX - 40f) }
        compose.mainClock.advanceTimeByFrame()
        state.value = state.value.copy(selectedTab = DirectoryTab.Contacts, hasUserSelectedTab = true)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Friend").assertIsDisplayed()
    }

    @Test fun `tapping the selected tab while a swipe settles keeps that tab`() {
        val group = GroupModel().apply { gid = "group"; name = "Project"; status = 0 }
        val state = mutableStateOf(assembleContactsState(ContactsSnapshot(friends = listOf(contact("friend", "Friend")),
            groups = listOf(group)), State(), DirectoryTab.Groups, NOW))
        val tabs = mutableListOf<DirectoryTab>()
        val onIntent: (Intent) -> Unit = { intent ->
            if (intent is Intent.SelectTab) {
                tabs += intent.tab
                state.value = state.value.copy(selectedTab = intent.tab, hasUserSelectedTab = true)
            }
        }
        compose.setContent {
            DifftTheme(applyWindowBackground = false) { ContactsScreen(state.value, onIntent, Modifier.size(400.dp, 650.dp)) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        
        compose.onNodeWithTag(CONTACTS_PAGER_TAG).performTouchInput { swipeLeft(startX = centerX + 150f, endX = centerX - 150f) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(context.getString(R.string.contact_groups)).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(DirectoryTab.Groups, state.value.selectedTab)
        assertEquals(DirectoryTab.Groups, tabs.last())
        compose.onNodeWithText("Project").assertIsDisplayed()
        
        compose.onNodeWithText("Friend").assertIsNotDisplayed()
    }

    @Test fun `official suffix and saved messages row retain their display rules`() {
        val state = assembleContactsState(ContactsSnapshot(
            friends = listOf(contact("self", "Zed"), contact("support", "Support Team")),
            selfId = "self", favoritesName = "Favorites", officialIds = setOf("support"),
        ), State(), DirectoryTab.Contacts, NOW)
        render(state)
        compose.onNodeWithText("Favorites").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.contacts_official)).assertIsDisplayed()
    }

    @Test fun `row timer disappears when the current snapshot turns it off`() {
        val snapshot = ContactsSnapshot(friends = listOf(contact("friend", "Friend")),
            messageExpirySeconds = mapOf("friend" to 86_400L))
        val state = mutableStateOf(assembleContactsState(snapshot, State(), DirectoryTab.Contacts, NOW))
        compose.setContent {
            DifftTheme(applyWindowBackground = false) { ContactsScreen(state.value, {}, Modifier.size(400.dp, 650.dp)) }
        }
        compose.onNodeWithText("1d").assertIsDisplayed()
        compose.runOnIdle { state.value = assembleContactsState(snapshot.copy(messageExpirySeconds = emptyMap()),
            state.value, DirectoryTab.Contacts, NOW) }
        compose.onNodeWithText("1d").assertDoesNotExist()
    }

    @Test fun `partially collapsed title keeps the full row measurement before clipping`() {
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                ContactsTitleBar(statusHeight = 0.dp, visibleHeight = 26.dp,
                    collapsedPx = with(density) { 26.dp.toPx() }, bandVisible = false,
                    controlsVisible = true, showAdd = true, onAdd = {})
            }
        }
        compose.onNodeWithTag(CONTACTS_TITLE_ROW_TAG).assertHeightIsEqualTo(52.dp)
    }

    private fun render(state: State, onIntent: (Intent) -> Unit = {}) {
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                ContactsScreen(state, onIntent, Modifier.size(400.dp, 650.dp), bottomObstruction = 72.dp)
            }
        }
        compose.waitForIdle()
    }

    private fun contact(id: String, name: String) = ContactorModel().apply { this.id = id; this.name = name }

    
    private fun withStrip(snapshot: ContactsSnapshot): ContactsSnapshot {
        val weak = contact("weak", "Pending")
        return snapshot.copy(friends = snapshot.friends + weak, weakContacts = listOf(weak),
            pending = mapOf("weak" to PendingContactMetadata(NOW + 1, NOW)))
    }
    private companion object { const val NOW = 1_900_000_000_000L }
}
