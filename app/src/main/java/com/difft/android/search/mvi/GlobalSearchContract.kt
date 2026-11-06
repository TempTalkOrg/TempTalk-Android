package com.difft.android.search.mvi

import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.recent.RoomViewData
import com.difft.android.chat.search.SearchChatHistoryViewData
import org.difft.app.database.models.ContactorModel

/** Global search (chats, contacts, groups, message history) of the home screen. */
object GlobalSearchContract {

    sealed interface Intent {
        /** The field's text changed; the search runs on the trimmed text after a debounce. */
        data class QueryChanged(val raw: String) : Intent

        /** The field was cleared. */
        data object Clear : Intent
    }

    /** What the empty area shows: the hint for an empty query, "no results", or nothing (results shown). */
    enum class Tips { DEFAULT, NO_RESULTS, NONE }

    data class State(
        /** The trimmed query the lists belong to. */
        val query: String = "",
        val recent: List<RoomViewData> = emptyList(),
        val contacts: List<ContactorModel> = emptyList(),
        val groups: List<GroupUIData> = emptyList(),
        val history: List<SearchChatHistoryViewData> = emptyList(),
        /** True from a non-empty query's search start until its four lists have all answered. */
        val searching: Boolean = false,
        val tips: Tips = Tips.DEFAULT,
    )
}
