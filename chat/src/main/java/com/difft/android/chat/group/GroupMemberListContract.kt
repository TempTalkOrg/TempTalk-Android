package com.difft.android.chat.group

import com.difft.android.chat.contacts.contactsall.toPinyinSortKey
import org.difft.app.database.models.ContactorModel

object GroupMemberListContract {
    data class Member(
        val id: String,
        val name: String,
        val contact: ContactorModel,
        val role: Int,
        
        val searchNames: List<String> = emptyList(),
    ) {
        val isModerator: Boolean get() = role <= GROUP_ROLE_ADMIN
    }

    data class State(
        val groupId: String = "",
        val loading: Boolean = true,
        val query: String = "",
        val members: List<Member> = emptyList(),
    )

    sealed interface Intent {
        data class QueryChanged(val query: String) : Intent
        data class Open(val id: String) : Intent
    }

    sealed interface Effect {
        data class OpenContact(val id: String, val groupId: String) : Effect
    }
}


internal fun List<GroupMemberListContract.Member>.sortedForMemberList(): List<GroupMemberListContract.Member> =
    sortedWith(compareBy({ it.role }, { it.name.toPinyinSortKey() }))


internal fun List<GroupMemberListContract.Member>.matching(query: String): List<GroupMemberListContract.Member> {
    val key = query.trim()
    if (key.isEmpty()) return this
    return filter { member ->
        member.name.contains(key, ignoreCase = true) || member.id.contains(key, ignoreCase = true) ||
            member.searchNames.any { it.contains(key, ignoreCase = true) }
    }
}
