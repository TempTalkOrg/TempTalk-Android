package com.difft.android.base.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.storage.di.AppStateDataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent











@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppStateDataStoreEntryPoint {
    @AppStateDataStore
    fun appStateDataStore(): DataStore<Preferences>
}
