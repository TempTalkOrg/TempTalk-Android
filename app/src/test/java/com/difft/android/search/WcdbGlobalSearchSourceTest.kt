package com.difft.android.search

import android.content.Context
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDB
import org.difft.app.database.getContactorFromAllTable
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.searchContactsByKeyword
import org.difft.app.database.searchOneOnOneRoomsByName
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals







class WcdbGlobalSearchSourceTest {

    @get:Rule
    val globals = GlobalStaticMockRule(testUserId = "me")

    private val wcdb: WCDB = mockk(relaxed = true)
    private val context: Context = mockk {
        every { getString(com.difft.android.base.R.string.chat_favorites) } returns "Saved"
    }
    private val source = WcdbGlobalSearchSource(context, wcdb)

    private var contactMatches: List<ContactorModel> = emptyList()
    private var roomMatches: List<RoomModel> = emptyList()
    private var cachedProfiles: List<ContactorModel> = emptyList()

    private fun contact(id: String, name: String) = ContactorModel().apply {
        this.id = id
        this.name = name
    }

    private fun room(id: String, name: String) = RoomModel().apply {
        roomId = id
        roomName = name
    }

    @Before
    fun setUp() {
        mockkStatic("org.difft.app.database.GlobalSearchQueriesKt", "org.difft.app.database.WCDBExtensionsKt")
        every { any<WCDB>().searchContactsByKeyword(any()) } answers { contactMatches }
        every { any<WCDB>().searchOneOnOneRoomsByName(any()) } answers { roomMatches }
        every { any<WCDB>().getContactorsFromAllTable(any(), any()) } answers { cachedProfiles.filter { it.id in secondArg<List<String>>() } }
        every { any<WCDB>().getContactorFromAllTable("me") } returns contact("me", "Owner")
    }

    @After
    fun tearDown() {
        unmockkStatic("org.difft.app.database.GlobalSearchQueriesKt", "org.difft.app.database.WCDBExtensionsKt")
    }

    @Test
    fun `S15 - a non-contact with a one-on-one conversation named after the query follows the contacts`() = runTest {
        contactMatches = listOf(contact("friend", "Alice Friend"))
        roomMatches = listOf(room("friend", "Alice Friend"), room("stranger", "Alice"), room("me", "Alice notes"))
        cachedProfiles = listOf(contact("stranger", "Alice W"))

        val result = source.contacts("Alice", limit = 25)

        assertEquals(listOf("friend", "stranger"), result.map { it.id }, "each person once, the own conversation never as a peer")
        assertEquals("Alice W", result[1].name, "the cached profile names the row")
    }

    @Test
    fun `a conversation peer without any cached profile is named after the conversation`() = runTest {
        roomMatches = listOf(room("ghost", "Alice"))

        assertEquals("Alice", source.contacts("ali", limit = 25).single().name)
    }

    @Test
    fun `conversation peers count against the cap after the contacts`() = runTest {
        contactMatches = (1..24).map { contact("c$it", "Alice $it") }
        roomMatches = listOf(room("p1", "Alice"), room("p2", "Alice"))

        val result = source.contacts("alice", limit = 25)

        assertEquals(25, result.size)
        assertEquals("p1", result.last().id)
    }

    @Test
    fun `S12 - searching the saved title puts the saved conversation first`() = runTest {
        contactMatches = listOf(contact("a", "Savannah"))

        val result = source.contacts("sav", limit = 25)

        assertEquals(listOf("me", "a"), result.map { it.id })
    }
}
