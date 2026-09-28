package com.difft.android.chat.translate.ui

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.translate.VisibleReason
import com.difft.android.chat.ui.ChatMessageAdapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ChatTranslateListBinder] — the fragment-side reporter of "which messages can the user see".
 *
 * The behaviour that matters is what it does NOT report: read receipts, reactions and send-status
 * updates commit a list every few hundred milliseconds, and reporting each one would restart the
 * visible-range batch continuously.
 *
 * What each case asserts:
 *  - T59 five commits over an unchanged window report nothing
 *  - T60 a commit that brings a new id reports once, as WINDOW_CHANGED
 *  - T61 a NO_POSITION layout manager yields an empty list rather than throwing
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatTranslateListBinderTest {

    private lateinit var recyclerView: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var adapter: ChatMessageAdapter
    private lateinit var binder: ChatTranslateListBinder

    private val reports = mutableListOf<Pair<List<String>, VisibleReason>>()

    @Before
    fun setUp() {
        layoutManager = mockk(relaxed = true)
        recyclerView = mockk(relaxed = true)
        every { recyclerView.layoutManager } returns layoutManager
        adapter = mockk(relaxed = true)

        binder = ChatTranslateListBinder(recyclerView, adapter) { ids, reason ->
            reports += ids to reason
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun window(vararg ids: String, first: Int = 0, last: Int = ids.lastIndex) {
        val list: List<ChatMessage> = ids.map { id -> TextChatMessage().apply { this.id = id } }
        every { adapter.currentList } returns list
        every { layoutManager.findFirstCompletelyVisibleItemPosition() } returns first
        every { layoutManager.findLastVisibleItemPosition() } returns last
    }

    // --- T59 ----------------------------------------------------------------

    @Test
    fun `T59 repeated commits over an unchanged window report nothing`() {
        window("m1", "m2", "m3")
        binder.onListCommitted()
        reports.clear()

        repeat(5) { binder.onListCommitted() }

        assertEquals(emptyList<Pair<List<String>, VisibleReason>>(), reports)
    }

    // --- T60 ----------------------------------------------------------------

    @Test
    fun `T60 a commit that brings a new id reports once as WINDOW_CHANGED`() {
        window("m1", "m2")
        binder.onListCommitted()
        reports.clear()

        window("m1", "m2", "m3")
        binder.onListCommitted()

        assertEquals(1, reports.size)
        assertEquals(VisibleReason.WINDOW_CHANGED, reports.single().second)
        assertEquals(listOf("m1", "m2", "m3"), reports.single().first)
    }

    @Test
    fun `T60 a commit that only drops ids reports nothing`() {
        window("m1", "m2", "m3")
        binder.onListCommitted()
        reports.clear()

        window("m1", "m2")
        binder.onListCommitted()

        assertEquals(emptyList<Pair<List<String>, VisibleReason>>(), reports)
    }

    // --- explicit triggers --------------------------------------------------

    @Test
    fun `the first render and every scroll idle report unconditionally`() {
        window("m1", "m2")

        binder.onFirstRender()
        binder.onScrollIdle()

        assertEquals(
            listOf(VisibleReason.FIRST_SCREEN, VisibleReason.SCROLL_IDLE),
            reports.map { it.second },
        )
    }

    @Test
    fun `only the completely visible head through the last partially visible row is reported`() {
        // A half-scrolled top row that grew by a translate block would push the viewport down.
        window("m1", "m2", "m3", "m4", first = 1, last = 2)

        binder.onScrollIdle()

        assertEquals(listOf("m2", "m3"), reports.single().first)
    }

    // --- T61 ----------------------------------------------------------------

    @Test
    fun `T61 NO_POSITION yields no report instead of throwing`() {
        window("m1", "m2")
        every { layoutManager.findFirstCompletelyVisibleItemPosition() } returns RecyclerView.NO_POSITION

        binder.onScrollIdle()
        binder.onFirstRender()

        assertEquals(emptyList<Pair<List<String>, VisibleReason>>(), reports)
    }

    @Test
    fun `T61 an inverted range yields no report`() {
        window("m1", "m2", "m3", first = 2, last = 1)

        binder.onScrollIdle()

        assertEquals(emptyList<Pair<List<String>, VisibleReason>>(), reports)
    }

    @Test
    fun `T61 a non-linear layout manager yields no report`() {
        window("m1", "m2")
        every { recyclerView.layoutManager } returns null

        binder.onScrollIdle()

        assertEquals(emptyList<Pair<List<String>, VisibleReason>>(), reports)
    }

    @Test
    fun `T61 a last position past the end of the list is tolerated`() {
        // The layout manager and the committed list can be one frame apart.
        window("m1", "m2", first = 0, last = 5)

        binder.onScrollIdle()

        assertEquals(listOf("m1", "m2"), reports.single().first)
    }
}
