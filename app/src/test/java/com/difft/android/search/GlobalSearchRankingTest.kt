package com.difft.android.search

import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.RoomModel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


class GlobalSearchRankingTest {

    private fun contact(id: String, name: String? = null, remark: String? = null, email: String? = null) =
        ContactorModel().apply {
            this.id = id
            this.name = name
            this.remark = remark
            this.email = email
        }

    private fun group(gid: String, name: String) = GroupModel().apply {
        this.gid = gid
        this.name = name
    }

    @Test
    fun `S4 and S13 - name prefix, name substring, email, id prefix, then an id substring`() {
        val idInside = contact("+1999ann0", name = "Zed")
        val email = contact("e", name = "Bob", email = "ann@x.org")
        val contains = contact("c", name = "Joanne")
        val idPrefix = contact("ann-id", name = "Yu")
        val remarkPrefix = contact("r", name = "Kim", remark = "Annie")

        val ranked = GlobalSearchRanking.rankContacts(listOf(idInside, email, contains, idPrefix, remarkPrefix), "ANN")

        assertEquals(listOf("r", "c", "e", "ann-id", "+1999ann0"), ranked.map { it.id })
    }

    @Test
    fun `equal ranks keep the stored order`() {
        val ranked = GlobalSearchRanking.rankContacts(listOf(contact("2", name = "Anna"), contact("1", name = "Annie")), "ann")

        assertEquals(listOf("2", "1"), ranked.map { it.id })
    }

    @Test
    fun `S5 - groups by name prefix, name substring, member-only, each most recently active first`() {
        val memberOld = group("m1", "Lunch")
        val memberNew = group("m2", "Dinner")
        val containsOld = group("c1", "The design crew")
        val prefixOld = group("p1", "Design A")
        val prefixNew = group("p2", "design B")
        val active = mapOf("m1" to 1L, "m2" to 9L, "c1" to 5L, "p1" to 2L, "p2" to 3L)

        val ranked = GlobalSearchRanking.rankGroups(listOf(memberOld, containsOld, prefixOld, memberNew, prefixNew), "Design", active)

        assertEquals(listOf("p2", "p1", "c1", "m2", "m1"), ranked.map { it.gid })
    }

    @Test
    fun `one-on-one conversations found by name rank prefix before substring`() {
        val rooms = listOf(
            RoomModel().apply { roomId = "a"; roomName = "Mary Alice" },
            RoomModel().apply { roomId = "b"; roomName = "Alice" },
        )

        assertEquals(listOf("b", "a"), GlobalSearchRanking.rankRoomsByName(rooms, "alice").map { it.roomId })
    }

    @Test
    fun `S12 - the saved conversation matches its shown title and the iOS aliases`() {
        assertTrue(GlobalSearchRanking.matchesSavedConversation("收藏", "收藏"))
        assertTrue(GlobalSearchRanking.matchesSavedConversation("sav", "Saved"))
        assertTrue(GlobalSearchRanking.matchesSavedConversation("备忘录", "Saved"))
        assertTrue(GlobalSearchRanking.matchesSavedConversation("Note", "Saved"))
        assertFalse(GlobalSearchRanking.matchesSavedConversation("no", "Saved"), "aliases match whole, not as substrings")
    }

    @Test
    fun `the saved conversation leads, contacts follow, conversation peers come last, each once and capped`() {
        val me = contact("me")
        val merged = GlobalSearchRanking.mergeContactHits(
            saved = me,
            contacts = listOf(contact("a"), me, contact("b")),
            conversations = listOf(contact("x"), contact("y")),
            limit = 4,
        )

        assertEquals(listOf("me", "a", "b", "x"), merged.map { it.id })
        assertEquals(5, GlobalSearchRanking.mergeContactHits(me, listOf(contact("a"), contact("b")), listOf(contact("x"), contact("y")), null).size)
    }
}
