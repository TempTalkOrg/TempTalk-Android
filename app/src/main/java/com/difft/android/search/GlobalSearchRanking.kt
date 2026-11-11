package com.difft.android.search

import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.RoomModel







internal object GlobalSearchRanking {

    
    private val SAVED_ALIASES = setOf("备忘录", "note")

    



    fun contactRank(contact: ContactorModel, key: String): Int {
        val names = listOfNotNull(contact.name, contact.remark, contact.publicName)
        return when {
            names.any { it.startsWith(key, ignoreCase = true) } -> RANK_PREFIX
            names.any { it.contains(key, ignoreCase = true) } -> RANK_CONTAINS
            contact.email?.contains(key, ignoreCase = true) == true -> RANK_EMAIL
            contact.id.startsWith(key, ignoreCase = true) -> RANK_ID_PREFIX
            else -> RANK_OTHER
        }
    }

    
    fun rankContacts(contacts: List<ContactorModel>, key: String): List<ContactorModel> =
        contacts.sortedBy { contactRank(it, key) }

    
    fun nameRank(name: String?, key: String): Int = when {
        name == null -> RANK_CONTAINS + 1
        name.startsWith(key, ignoreCase = true) -> RANK_PREFIX
        name.contains(key, ignoreCase = true) -> RANK_CONTAINS
        else -> RANK_CONTAINS + 1
    }

    
    fun rankRoomsByName(rooms: List<RoomModel>, key: String): List<RoomModel> =
        rooms.sortedBy { nameRank(it.roomName, key) }

    



    fun rankGroups(groups: List<GroupModel>, key: String, lastActive: Map<String, Long>): List<GroupModel> =
        groups.sortedWith(
            compareBy<GroupModel> { nameRank(it.name, key) }
                .thenByDescending { lastActive[it.gid.orEmpty()] ?: 0L }
        )

    



    fun matchesSavedConversation(key: String, savedTitle: String): Boolean =
        savedTitle.contains(key, ignoreCase = true) || key.lowercase() in SAVED_ALIASES

    




    fun mergeContactHits(
        saved: ContactorModel?,
        contacts: List<ContactorModel>,
        conversations: List<ContactorModel>,
        limit: Int?,
    ): List<ContactorModel> {
        val merged = (listOfNotNull(saved) + contacts + conversations).distinctBy { it.id }
        return if (limit == null) merged else merged.take(limit)
    }

    private const val RANK_PREFIX = 1
    private const val RANK_CONTAINS = 2
    private const val RANK_EMAIL = 3
    private const val RANK_ID_PREFIX = 4
    private const val RANK_OTHER = 5
}
