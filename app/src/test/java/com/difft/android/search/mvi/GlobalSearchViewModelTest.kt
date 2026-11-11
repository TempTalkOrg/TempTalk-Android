package com.difft.android.search.mvi

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.search.GlobalSearchSource
import com.difft.android.search.mvi.GlobalSearchContract.Intent
import com.difft.android.search.mvi.GlobalSearchContract.Scope
import com.difft.android.search.mvi.GlobalSearchContract.Section
import com.difft.android.test.TestDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.difft.app.database.models.ContactorModel
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue






@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {

    @get:Rule
    val dispatchers = TestDispatcherRule(StandardTestDispatcher())

    private class ScriptedSource : GlobalSearchSource {
        val keys = mutableListOf<String>()
        val contactLimits = mutableListOf<Int?>()
        var contacts: List<ContactorModel> = emptyList()
        var groups: List<GroupUIData> = emptyList()
        var history: List<SearchChatHistoryViewData> = emptyList()
        val historyByKey = mutableMapOf<String, List<SearchChatHistoryViewData>>()

        
        var historyGate: Pair<String, CompletableDeferred<Unit>>? = null

        override suspend fun contacts(key: String, limit: Int?): List<ContactorModel> {
            keys += key
            contactLimits += limit
            return if (limit == null) contacts else contacts.take(limit)
        }

        override suspend fun groups(key: String, limit: Int?): List<GroupUIData> = if (limit == null) groups else groups.take(limit)

        override suspend fun chatHistory(key: String): List<SearchChatHistoryViewData> {
            historyGate?.takeIf { it.first == key }?.second?.await()
            return historyByKey[key] ?: history
        }
    }

    private val source = ScriptedSource()
    private val handle = SavedStateHandle()
    private val viewModel by lazy { GlobalSearchViewModel(source, handle) }

    private fun advance(ms: Long) {
        dispatchers.testDispatcher.scheduler.advanceTimeBy(ms)
        dispatchers.testDispatcher.scheduler.runCurrent()
    }

    private fun search(query: String) {
        viewModel.dispatch(Intent.QueryChanged(query))
        advance(GlobalSearchViewModel.DEBOUNCE_MS)
    }

    private fun contacts(n: Int) = (1..n).map { i -> ContactorModel().apply { id = "c$i" } }
    private fun groups(n: Int) = (1..n).map { i -> GroupUIData(gid = "g$i", members = emptyList()) }
    private fun rooms(n: Int) = (1..n).map { i -> SearchChatHistoryViewData("r$i") }

    private fun rows(): List<SearchRow> = viewModel.state.value.rows

    @Test
    fun `typing searches the trimmed text once the 150 ms debounce has passed`() = runTest {
        viewModel.dispatch(Intent.QueryChanged(" ab"))
        advance(100)
        viewModel.dispatch(Intent.QueryChanged(" abc "))
        advance(149)
        assertTrue(source.keys.isEmpty(), "still debouncing")

        advance(1)

        assertEquals(listOf("abc"), source.keys)
        assertEquals("abc", viewModel.state.value.query)
    }

    @Test
    fun `a query equal to the current one is dropped`() = runTest {
        search("abc")
        search("abc ")

        assertEquals(listOf("abc"), source.keys, "the restored text re-fires onQueryChanged without a new search")
    }

    @Test
    fun `the three lists land in state and searching ends with the last answer`() = runTest {
        source.contacts = contacts(1)
        source.groups = groups(1)
        val gate = CompletableDeferred<Unit>()
        source.historyGate = "abc" to gate

        viewModel.state.test {
            awaitItem()
            search("abc")
            
            val latest = expectMostRecentItem()
            assertEquals(listOf("c1"), latest.contacts.map { it.id })
            assertEquals(listOf("g1"), latest.groups.map { it.gid })
            assertTrue(latest.searching)

            source.history = rooms(1)
            gate.complete(Unit)
            advance(0)
            val done = expectMostRecentItem()
            assertFalse(done.searching)
            assertEquals(listOf("r1"), done.history.map { it.conversationId })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf<Int?>(GlobalSearchViewModel.RESULT_LIMIT), source.contactLimits, "contacts and groups are capped at 25")
    }

    @Test
    fun `S1 - All shows three rows per section and a View all row with the section's full count`() = runTest {
        source.contacts = contacts(5)
        source.groups = groups(2)
        source.history = rooms(4)

        search("abc")

        val query = "abc"
        assertEquals(
            listOf(
                SearchRow.Scopes(Scope.ALL),
                SearchRow.Header(Section.CONTACTS),
                SearchRow.Contact(source.contacts[0], query, divider = true),
                SearchRow.Contact(source.contacts[1], query, divider = true),
                SearchRow.Contact(source.contacts[2], query, divider = true),
                SearchRow.ViewAll(Section.CONTACTS, 5),
                SearchRow.Header(Section.GROUPS),
                SearchRow.Group(source.groups[0], query, divider = true),
                SearchRow.Group(source.groups[1], query, divider = false),
                SearchRow.Header(Section.MESSAGES),
                SearchRow.Message(source.history[0], query, divider = true),
                SearchRow.Message(source.history[1], query, divider = true),
                SearchRow.Message(source.history[2], query, divider = true),
                SearchRow.ViewAll(Section.MESSAGES, 4),
            ),
            rows(),
        )
    }

    @Test
    fun `S2 - choosing the contacts scope shows only contacts, all of them, without a more row`() = runTest {
        source.contacts = contacts(5)
        source.groups = groups(2)
        search("abc")

        viewModel.dispatch(Intent.SelectScope(Scope.CONTACTS))

        val rows = rows()
        assertEquals(SearchRow.Scopes(Scope.CONTACTS), rows.first())
        assertEquals(listOf(SearchRow.Header(Section.CONTACTS)), rows.filterIsInstance<SearchRow.Header>())
        assertEquals(source.contacts, rows.filterIsInstance<SearchRow.Contact>().map { it.contact })
        assertFalse(rows.filterIsInstance<SearchRow.Contact>().last().divider, "the section's last row has no hairline")
        assertTrue(rows.none { it is SearchRow.More || it is SearchRow.ViewAll })
    }

    @Test
    fun `S3 - a full capped lookup offers View more in its own scope only`() = runTest {
        source.contacts = contacts(30)
        source.groups = groups(25)
        search("abc")

        assertTrue(SearchRow.ViewAll(Section.CONTACTS, 25) in rows(), "All counts what the capped lookup returned")

        viewModel.dispatch(Intent.SelectScope(Scope.CONTACTS))
        assertEquals(25, rows().count { it is SearchRow.Contact })
        assertEquals(SearchRow.More(Section.CONTACTS), rows().last())
        assertTrue(rows().filterIsInstance<SearchRow.Contact>().last().divider, "the more row follows the last contact")

        viewModel.dispatch(Intent.SelectScope(Scope.GROUPS))
        assertEquals(SearchRow.More(Section.GROUPS), rows().last())
    }

    @Test
    fun `S14 - the message history scope lists every conversation with no cap`() = runTest {
        source.history = rooms(201)
        search("abc")

        viewModel.dispatch(Intent.SelectScope(Scope.MESSAGES))

        assertEquals(201, rows().count { it is SearchRow.Message })
        assertTrue(rows().none { it is SearchRow.More || it is SearchRow.ViewAll })
    }

    @Test
    fun `S8 - an empty query shows the hint and no match the no-results state, both under the pills`() = runTest {
        assertEquals(listOf(SearchRow.Scopes(Scope.ALL), SearchRow.Empty("")), rows())

        search("zzz")
        assertEquals(listOf(SearchRow.Scopes(Scope.ALL), SearchRow.Empty("zzz")), rows())

        source.contacts = contacts(1)
        search("abc")
        viewModel.dispatch(Intent.SelectScope(Scope.GROUPS))
        assertEquals(listOf(SearchRow.Scopes(Scope.GROUPS), SearchRow.Empty("abc")), rows(), "nothing in this scope")
    }

    @Test
    fun `no premature no-results while the lists are still answering`() = runTest {
        val gate = CompletableDeferred<Unit>()
        source.historyGate = "abc" to gate
        search("abc")

        assertEquals(listOf<SearchRow>(SearchRow.Scopes(Scope.ALL)), rows())
        gate.complete(Unit)
        advance(0)
        assertEquals(SearchRow.Empty("abc"), rows().last())
    }

    @Test
    fun `a new query or a cleared field keeps the chosen scope`() = runTest {
        source.groups = groups(1)
        search("abc")
        viewModel.dispatch(Intent.SelectScope(Scope.GROUPS))

        search("abd")
        assertEquals(Scope.GROUPS, viewModel.state.value.scope)

        viewModel.dispatch(Intent.Clear)
        advance(GlobalSearchViewModel.DEBOUNCE_MS)
        assertEquals(GlobalSearchContract.State(scope = Scope.GROUPS, rows = listOf(SearchRow.Scopes(Scope.GROUPS), SearchRow.Empty(""))), viewModel.state.value)
    }

    @Test
    fun `S11 - the scope survives a process death through the saved state`() = runTest {
        viewModel.dispatch(Intent.SelectScope(Scope.GROUPS))

        val restored = GlobalSearchViewModel(source, SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))

        assertEquals(Scope.GROUPS, restored.state.value.scope)
        assertEquals(SearchRow.Scopes(Scope.GROUPS), restored.state.value.rows.first())
    }

    @Test
    fun `a keystroke that collapses to the same query leaves the running search alone`() = runTest {
        val gate = CompletableDeferred<Unit>()
        source.historyGate = "abc" to gate
        source.historyByKey["abc"] = rooms(1)
        search("abc")
        assertTrue(viewModel.state.value.searching)

        search("abc ")
        gate.complete(Unit)
        advance(0)

        val state = viewModel.state.value
        assertEquals(listOf("r1"), state.history.map { it.conversationId }, "the running search still answers")
        assertFalse(state.searching)
    }

    @Test
    fun `a newer query supersedes a slower one`() = runTest {
        val slow = CompletableDeferred<Unit>()
        source.historyGate = "first" to slow
        source.historyByKey["first"] = listOf(SearchChatHistoryViewData("stale"))
        source.historyByKey["second"] = listOf(SearchChatHistoryViewData("fresh"))
        search("first")

        search("second")
        slow.complete(Unit)
        advance(0)

        val state = viewModel.state.value
        assertEquals("second", state.query)
        assertEquals(listOf("fresh"), state.history.map { it.conversationId })
        assertFalse(state.searching)
    }
}
