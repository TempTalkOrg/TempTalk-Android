package com.difft.android.chat.contacts.data

import com.difft.android.chat.contacts.compose.DirectoryTab
import com.difft.android.chat.contacts.mvi.ContactsContract.State
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContactsSnapshotTest {
    @Before fun clearRemarks() { ContactRemarkCache.clear() }

    @Test fun `pending contacts excluded from AZ and deduplicated with live values and newest deletion tie break`() {
        val pending = contact("pending", "Old")
        val snapshot = ContactsSnapshot(
            friends = listOf(contact("alice", "Alice"), contact("alice", "Alice"), pending),
            weakContacts = listOf(contact("pending", "Live"), contact("new", "New"), contact("last", "Last")),
            pending = mapOf("pending" to PendingContactMetadata(NOW + DAY, 5),
                "new" to PendingContactMetadata(NOW + 1, 10),
                "last" to PendingContactMetadata(NOW + DAY + 1, 20)),
        )
        val state = assembleContactsState(snapshot, State(), DirectoryTab.Contacts, NOW)
        assertEquals(listOf("new", "pending", "last"), state.pendingRemoval.map { it.row.id })
        assertEquals(listOf(1, 1, 2), state.pendingRemoval.map { it.daysLeft })
        assertEquals(listOf(0, 0, 2), state.pendingRemoval.map { it.displayDays })
        assertEquals("Live", state.pendingRemoval[1].row.displayName)
        assertEquals(listOf("alice"), state.contactSections.flatMap { it.rows }.map { it.id })
        assertEquals(listOf("A"), state.indexLetters)
    }

    @Test fun `expired weak placeholder remains visible as today until server removal`() {
        val snapshot = ContactsSnapshot(weakContacts = listOf(contact("weak", "Weak")),
            pending = mapOf("weak" to PendingContactMetadata(NOW - DAY, 1)))
        val state = assembleContactsState(snapshot, State(), DirectoryTab.Contacts, NOW)
        assertEquals(1, state.pendingRemoval.single().daysLeft)
    }

    @Test fun `active expiring groups only sorted by deadline with day ceiling and member count`() {
        val snapshot = ContactsSnapshot(
            groups = listOf(group("late", "Z", NOW + DAY + 1), group("early", "Alpha", NOW + 1),
                group("expired", "Expired", NOW), group("normal", "Normal", null),
                group("gone", "Gone", NOW + DAY).apply { status = 1 }),
            memberCounts = mapOf("early" to 7),
        )
        val state = assembleContactsState(snapshot, State(), DirectoryTab.Groups, NOW)
        assertEquals(listOf("early", "late"), state.expiringGroups.map { it.row.id })
        assertEquals(listOf(1, 2), state.expiringGroups.map { it.daysLeft })
        assertEquals(7, state.expiringGroups.first().row.memberCount)
        assertEquals(60L, state.expiringGroups.first().row.messageExpirySeconds)
        assertEquals(listOf("A", "E", "N", "Z"), state.indexLetters)
        assertFalse(state.groupSections.flatMap { it.rows }.any { it.id == "gone" })
    }

    @Test fun `self title changes only display while official flag and timer keep real names for sorting`() {
        val snapshot = ContactsSnapshot(friends = listOf(contact("self", "Zed"), contact("support", "Support Team")),
            officialIds = setOf("self", "support"), selfId = "self", favoritesName = "Favorites",
            messageExpirySeconds = mapOf("support" to 3600L), textSize = 1)
        val state = assembleContactsState(snapshot, State(selectedId = "support"), DirectoryTab.Contacts, NOW)
        assertEquals(listOf("S", "Z"), state.indexLetters)
        val support = state.contactSections.first().rows.single()
        assertEquals("Support Team", support.displayName)
        assertTrue(support.isOfficial)
        assertEquals(3600L, support.messageExpirySeconds)
        val self = state.contactSections.last().rows.single()
        assertEquals("Favorites", self.displayName)
        assertTrue(self.isSelf)
        assertFalse(self.isOfficial)
        assertEquals("support", state.selectedId)
        assertEquals(1, state.textSize)
    }

    @Test fun `empty snapshots produce empty strips and sections and switching tabs updates index`() {
        val empty = assembleContactsState(ContactsSnapshot(), State(), DirectoryTab.Groups, NOW)
        assertTrue(empty.pendingRemoval.isEmpty())
        assertTrue(empty.expiringGroups.isEmpty())
        assertTrue(empty.indexLetters.isEmpty())
        val state = assembleContactsState(ContactsSnapshot(friends = listOf(contact("a", "Alice")),
            groups = listOf(group("g", "Zoo", null))), empty, DirectoryTab.Groups, NOW)
        assertEquals(listOf("Z"), state.indexLetters)
        assertEquals(listOf("A"), selectDirectoryTab(state, DirectoryTab.Contacts).indexLetters)
    }

    private fun contact(uid: String, display: String) = ContactorModel().apply { id = uid; name = display }
    private fun group(id: String, display: String, expiry: Long?) = GroupModel().apply {
        gid = id; name = display; status = 0; expireTime = expiry; messageExpiry = 60
    }
    private companion object {
        const val NOW = 1_000_000L
        const val DAY = 86_400_000L
    }
}
