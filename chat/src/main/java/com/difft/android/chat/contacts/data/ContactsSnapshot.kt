package com.difft.android.chat.contacts.data

import com.difft.android.base.utils.weakcontact.WeakContactCountdown
import com.difft.android.chat.contacts.compose.DirectoryTab
import com.difft.android.chat.contacts.mvi.ContactDirectoryRow
import com.difft.android.chat.contacts.mvi.ContactsContract.State
import com.difft.android.chat.contacts.mvi.ExpiringGroupTile
import com.difft.android.chat.contacts.mvi.GroupDirectoryRow
import com.difft.android.chat.contacts.mvi.PendingRemovalTile
import com.difft.android.chat.group.groupDissolveDaysLeft
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel


data class ContactsSnapshot(
    val friends: List<ContactorModel> = emptyList(),
    val weakContacts: List<ContactorModel> = emptyList(),
    val pending: Map<String, PendingContactMetadata> = emptyMap(),
    val groups: List<GroupModel> = emptyList(),
    val memberCounts: Map<String, Int> = emptyMap(),
    val messageExpirySeconds: Map<String, Long> = emptyMap(),
    val officialIds: Set<String> = emptySet(),
    val selfId: String = "",
    val favoritesName: String = "",
    val textSize: Int = 0,
    
    val unresolvedWeakIds: List<String> = emptyList(),
)

data class PendingContactMetadata(val expireAt: Long, val deletedAt: Long)


internal fun assembleContactsState(
    snapshot: ContactsSnapshot,
    current: State,
    selectedTab: DirectoryTab,
    now: Long,
): State {
    fun row(contact: ContactorModel) = ContactDirectoryRow(
        id = contact.id,
        displayName = if (contact.id == snapshot.selfId) snapshot.favoritesName else contact.getDisplayNameForUI(),
        contact = contact,
        isSelf = contact.id == snapshot.selfId,
        isOfficial = contact.id != snapshot.selfId && contact.id in snapshot.officialIds,
        messageExpirySeconds = snapshot.messageExpirySeconds[contact.id]?.coerceAtLeast(0) ?: 0,
    )
    
    val weakById = snapshot.weakContacts.associateBy { it.id }
    val allById = snapshot.friends.associateBy { it.id } + weakById
    val pending = snapshot.pending.mapNotNull { (id, metadata) ->
        allById[id]?.let {
            val days = WeakContactCountdown.daysLeft(now, 0, 0, metadata.expireAt)
            PendingRemovalTile(
                row(it), days, if (days <= 1) 0 else days,
                metadata.expireAt, metadata.deletedAt,
            )
        }
    }.sortedWith(compareBy<PendingRemovalTile> { it.daysLeft }.thenByDescending { it.deletedAt })
    val contacts = directorySections(
        snapshot.friends.distinctBy { it.id }.filter { it.id !in snapshot.pending }.map(::row),
    ) { it.contact.getDisplayNameForUI() }
    val groups = snapshot.groups.filter { it.status == 0 && !it.gid.isNullOrEmpty() }
        .distinctBy { it.gid }.map { group ->
            val id = requireNotNull(group.gid)
            GroupDirectoryRow(id, group.name.orEmpty(), group, snapshot.memberCounts[id] ?: 0,
                (group.messageExpiry?.toLong() ?: 0).coerceAtLeast(0))
        }
    val groupSections = directorySections(groups) { it.displayName }
    val expiring = groups.mapNotNull { group ->
        group.group.expireTime?.takeIf { it > now }?.let {
            ExpiringGroupTile(group, groupDissolveDaysLeft(it, now), it)
        }
    }.sortedBy { it.expireTime }
    return current.copy(
        pendingRemoval = pending,
        expiringGroups = expiring,
        selectedTab = selectedTab,
        contactSections = contacts,
        groupSections = groupSections,
        indexLetters = if (selectedTab == DirectoryTab.Contacts) contacts.map { it.letter } else groupSections.map { it.letter },
        textSize = snapshot.textSize,
        initialized = true,
    )
}

internal fun selectDirectoryTab(state: State, tab: DirectoryTab): State = state.copy(
    selectedTab = tab,
    indexLetters = if (tab == DirectoryTab.Contacts) state.contactSections.map { it.letter }
        else state.groupSections.map { it.letter },
)
