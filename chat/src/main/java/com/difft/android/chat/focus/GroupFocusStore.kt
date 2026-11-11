package com.difft.android.chat.focus

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class GroupFocusStore @Inject constructor(
    @param:AppStateDataStore private val dataStore: DataStore<Preferences>,
    private val gson: Gson,
) {
    
    suspend fun read(): GroupFocusState {
        val raw = runCatching { dataStore.data.first()[AppStateKeys.GROUP_FOCUS_STATE] }
            .onFailure { L.w { "[GroupFocus] store read failed: ${it.message}" } }
            .getOrNull()
        if (raw.isNullOrBlank()) return GroupFocusState()
        return runCatching { gson.fromJson(raw, GroupFocusState::class.java) ?: GroupFocusState() }
            .onFailure { L.w { "[GroupFocus] store decode failed: ${it.message}" } }
            .getOrDefault(GroupFocusState())
    }

    suspend fun write(state: GroupFocusState) {
        runCatching {
            dataStore.edit { it[AppStateKeys.GROUP_FOCUS_STATE] = gson.toJson(state) }
        }.onFailure { L.w { "[GroupFocus] store write failed: ${it.message}" } }
    }
}
