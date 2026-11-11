package com.difft.android.search.mvi

import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.search.SearchChatHistoryViewData
import org.difft.app.database.models.ContactorModel


object GlobalSearchContract {

    sealed interface Intent {
        
        data class QueryChanged(val raw: String) : Intent

        
        data object Clear : Intent

        
        data class SelectScope(val scope: Scope) : Intent
    }

    
    enum class Scope { ALL, CONTACTS, GROUPS, MESSAGES }

    
    enum class Section(val scope: Scope) {
        CONTACTS(Scope.CONTACTS),
        GROUPS(Scope.GROUPS),
        MESSAGES(Scope.MESSAGES),
    }

    data class State(
        
        val query: String = "",
        val scope: Scope = Scope.ALL,
        
        val contacts: List<ContactorModel> = emptyList(),
        
        val groups: List<GroupUIData> = emptyList(),
        
        val history: List<SearchChatHistoryViewData> = emptyList(),
        
        val searching: Boolean = false,
        
        val rows: List<SearchRow> = emptyList(),
    )
}
