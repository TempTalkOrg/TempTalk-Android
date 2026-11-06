package com.difft.android.search.mvi

import app.cash.turbine.test
import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.recent.RoomViewData
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.search.GlobalSearchSource
import com.difft.android.search.mvi.GlobalSearchContract.Intent
import com.difft.android.search.mvi.GlobalSearchContract.Tips
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

/**
 * [GlobalSearchViewModel]: the 300 ms debounce, the same-query drop, the four lists landing
 * independently and a newer query superseding a slower one (the logic moved from the former
 * SearchActivity, design "global search in the list pane" §4.3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {

    @get:Rule
    val dispatchers = TestDispatcherRule(StandardTestDispatcher())

    private class ScriptedSource : GlobalSearchSource {
        val keys = mutableListOf<String>()
        var recent: List<RoomViewData> = emptyList()
        var contacts: List<ContactorModel> = emptyList()
        var groups: List<GroupUIData> = emptyList()
        var history: List<SearchChatHistoryViewData> = emptyList()
        val historyByKey = mutableMapOf<String, List<SearchChatHistoryViewData>>()

        /** When set, the history lookup for that key waits until the deferred completes. */
        var historyGate: Pair<String, CompletableDeferred<Unit>>? = null

        override suspend fun recentChats(key: String): List<RoomViewData> {
            keys += key
            return recent
        }

        override suspend fun contacts(key: String): List<ContactorModel> = contacts
        override suspend fun groups(key: String): List<GroupUIData> = groups
        override suspend fun chatHistory(key: String, maxRooms: Int?): List<SearchChatHistoryViewData> {
            historyGate?.takeIf { it.first == key }?.second?.await()
            return historyByKey[key] ?: history
        }
    }

    private val source = ScriptedSource()
    private val viewModel by lazy { GlobalSearchViewModel(source) }

    private fun advance(ms: Long) {
        dispatchers.testDispatcher.scheduler.advanceTimeBy(ms)
        dispatchers.testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `typing searches the trimmed text once the debounce has passed`() = runTest {
        viewModel.dispatch(Intent.QueryChanged(" ab"))
        advance(100)
        viewModel.dispatch(Intent.QueryChanged(" abc "))
        advance(299)
        assertTrue(source.keys.isEmpty(), "still debouncing")

        advance(1)

        assertEquals(listOf("abc"), source.keys)
        assertEquals("abc", viewModel.state.value.query)
    }

    @Test
    fun `a query equal to the current one is dropped`() = runTest {
        viewModel.dispatch(Intent.QueryChanged("abc"))
        advance(300)
        viewModel.dispatch(Intent.QueryChanged("abc "))
        advance(300)

        assertEquals(listOf("abc"), source.keys, "the restored text re-fires onQueryChanged without a new search")
    }

    @Test
    fun `the four lists land in state and searching ends with the last answer`() = runTest {
        source.recent = listOf(RoomViewData(roomId = "r1"))
        source.contacts = listOf(ContactorModel().apply { id = "c1" })
        source.groups = listOf(GroupUIData(gid = "g1", members = emptyList()))
        val gate = CompletableDeferred<Unit>()
        source.historyGate = "abc" to gate

        viewModel.state.test {
            assertEquals(Tips.DEFAULT, awaitItem().tips)
            viewModel.dispatch(Intent.QueryChanged("abc"))
            advance(300)
            // Three answers have arrived; the gated history keeps searching true.
            val latest = expectMostRecentItem()
            assertEquals(listOf("r1"), latest.recent.map { it.roomId })
            assertEquals(listOf("c1"), latest.contacts.map { it.id })
            assertEquals(listOf("g1"), latest.groups.map { it.gid })
            assertTrue(latest.searching)
            assertEquals(Tips.NONE, latest.tips)

            source.history = listOf(SearchChatHistoryViewData("r1"))
            gate.complete(Unit)
            advance(0)
            val done = expectMostRecentItem()
            assertFalse(done.searching)
            assertEquals(listOf("r1"), done.history.map { it.conversationId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no match in any list shows the no-results tip and an empty query the default one`() = runTest {
        viewModel.dispatch(Intent.QueryChanged("zzz"))
        advance(300)
        assertEquals(Tips.NO_RESULTS, viewModel.state.value.tips)

        viewModel.dispatch(Intent.Clear)
        advance(300)
        assertEquals(GlobalSearchContract.State(), viewModel.state.value)
    }

    @Test
    fun `a keystroke that collapses to the same query leaves the running search alone`() = runTest {
        val gate = CompletableDeferred<Unit>()
        source.historyGate = "abc" to gate
        source.historyByKey["abc"] = listOf(SearchChatHistoryViewData("r1"))
        viewModel.dispatch(Intent.QueryChanged("abc"))
        advance(300)
        assertTrue(viewModel.state.value.searching)

        viewModel.dispatch(Intent.QueryChanged("abc "))
        advance(300)
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
        viewModel.dispatch(Intent.QueryChanged("first"))
        advance(300)

        viewModel.dispatch(Intent.QueryChanged("second"))
        advance(300)
        slow.complete(Unit)
        advance(0)

        val state = viewModel.state.value
        assertEquals("second", state.query)
        assertEquals(listOf("fresh"), state.history.map { it.conversationId })
        assertFalse(state.searching)
    }
}
