package com.difft.android.search.mvi

import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.search.mvi.GlobalSearchContract.Scope
import com.difft.android.search.mvi.GlobalSearchContract.Section
import org.difft.app.database.models.ContactorModel






sealed interface SearchRow {
    
    val key: String

    
    data class Scopes(val selected: Scope) : SearchRow {
        override val key: String get() = "scopes"
    }

    data class Header(val section: Section) : SearchRow {
        override val key: String get() = "header:$section"
    }

    data class Contact(val contact: ContactorModel, val query: String, val divider: Boolean) : SearchRow {
        override val key: String get() = "contact:${contact.id}"
    }

    data class Group(val group: GroupUIData, val query: String, val divider: Boolean) : SearchRow {
        override val key: String get() = "group:${group.gid}"
    }

    data class Message(val history: SearchChatHistoryViewData, val query: String, val divider: Boolean) : SearchRow {
        override val key: String get() = "message:${history.conversationId}"
    }

    
    data class ViewAll(val section: Section, val count: Int) : SearchRow {
        override val key: String get() = "all:$section"
    }

    
    data class More(val section: Section) : SearchRow {
        override val key: String get() = "more:$section"
    }

    
    data class Empty(val query: String) : SearchRow {
        override val key: String get() = "empty"
    }
}


internal const val ALL_SCOPE_ROWS = 3








internal fun buildSearchRows(state: GlobalSearchContract.State, resultLimit: Int): List<SearchRow> = buildList {
    add(SearchRow.Scopes(state.scope))
    val query = state.query
    if (query.isEmpty()) {
        add(SearchRow.Empty(""))
        return@buildList
    }
    addSection(Section.CONTACTS, state.contacts, state.scope, resultLimit) { item, divider -> SearchRow.Contact(item, query, divider) }
    addSection(Section.GROUPS, state.groups, state.scope, resultLimit) { item, divider -> SearchRow.Group(item, query, divider) }
    addSection(Section.MESSAGES, state.history, state.scope, resultLimit) { item, divider -> SearchRow.Message(item, query, divider) }
    
    if (size == 1 && !state.searching) add(SearchRow.Empty(query))
}

private fun <T> MutableList<SearchRow>.addSection(
    section: Section,
    items: List<T>,
    scope: Scope,
    resultLimit: Int,
    row: (T, Boolean) -> SearchRow,
) {
    if (items.isEmpty() || (scope != Scope.ALL && scope != section.scope)) return
    add(SearchRow.Header(section))
    val cut = scope == Scope.ALL && items.size > ALL_SCOPE_ROWS
    
    val more = scope != Scope.ALL && section != Section.MESSAGES && items.size >= resultLimit
    val shown = if (cut) items.take(ALL_SCOPE_ROWS) else items
    val trailer = cut || more
    shown.forEachIndexed { index, item -> add(row(item, index < shown.lastIndex || trailer)) }
    when {
        cut -> add(SearchRow.ViewAll(section, items.size))
        more -> add(SearchRow.More(section))
    }
}
