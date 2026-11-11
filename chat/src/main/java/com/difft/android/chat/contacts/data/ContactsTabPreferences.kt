package com.difft.android.chat.contacts.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.chat.contacts.compose.DirectoryTab
import javax.inject.Inject
import kotlinx.coroutines.flow.first


class ContactsTabPreferences @Inject constructor(
    @param:AppStateDataStore private val store: DataStore<Preferences>,
) {
    suspend fun read(): DirectoryTab? = decodeDirectoryTab(store.data.first()[KEY])

    suspend fun save(tab: DirectoryTab) {
        store.edit { it[KEY] = tab.name }
    }

    internal companion object {
        val KEY = stringPreferencesKey("contacts_directory_tab")
    }
}

internal fun decodeDirectoryTab(value: String?): DirectoryTab? = DirectoryTab.entries.find { it.name == value }

internal fun initialDirectoryTab(saved: DirectoryTab?, hasGroups: Boolean): DirectoryTab =
    saved ?: if (hasGroups) DirectoryTab.Groups else DirectoryTab.Contacts
