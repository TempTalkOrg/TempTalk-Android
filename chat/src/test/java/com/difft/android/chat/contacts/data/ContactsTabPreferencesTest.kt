package com.difft.android.chat.contacts.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.difft.android.chat.contacts.compose.DirectoryTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContactsTabPreferencesTest {
    @Test fun `first visit uses groups only when available and remembered tab always wins`() {
        assertEquals(DirectoryTab.Groups, initialDirectoryTab(null, true))
        assertEquals(DirectoryTab.Contacts, initialDirectoryTab(null, false))
        assertEquals(DirectoryTab.Contacts, initialDirectoryTab(DirectoryTab.Contacts, true))
        assertEquals(DirectoryTab.Groups, initialDirectoryTab(DirectoryTab.Groups, false))
        assertNull(decodeDirectoryTab("invalid"))
    }

    @Test fun `selection persists across preference facade recreation with exact device key`() = runTest {
        val store = MemoryPreferences()
        val preferences = ContactsTabPreferences(store)
        assertNull(preferences.read())
        preferences.save(DirectoryTab.Groups)
        assertEquals("contacts_directory_tab", ContactsTabPreferences.KEY.name)
        assertEquals(DirectoryTab.Groups, ContactsTabPreferences(store).read())
        preferences.save(DirectoryTab.Contacts)
        assertEquals(DirectoryTab.Contacts, ContactsTabPreferences(store).read())
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
