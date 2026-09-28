package com.difft.android.chat.attachment.migration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory stand-in for the `app_state` DataStore: the real one needs an Android context, the
 * migration's progress rules do not.
 */
internal class FakePreferencesDataStore : DataStore<Preferences> {
    private val preferences = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = preferences

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(preferences.value)
        preferences.value = updated
        return updated
    }
}
