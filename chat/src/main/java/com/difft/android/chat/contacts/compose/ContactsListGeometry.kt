package com.difft.android.chat.contacts.compose

import com.difft.android.chat.contacts.mvi.ContactsContract.State


internal fun directorySectionIndices(state: State, tab: DirectoryTab = state.selectedTab): Map<String, Int> {
    var item = 1
    val sections = if (tab == DirectoryTab.Contacts) state.contactSections.map { it.letter to it.rows.size }
        else state.groupSections.map { it.letter to it.rows.size }
    return buildMap {
        sections.forEach { (letter, count) ->
            put(letter, item)
            item += count + 1
        }
    }
}
