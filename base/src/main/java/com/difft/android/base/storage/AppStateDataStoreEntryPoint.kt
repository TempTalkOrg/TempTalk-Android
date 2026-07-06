package com.difft.android.base.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.storage.di.AppStateDataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry-point for non-Hilt classes (custom Views, [object] singletons, etc.)
 * that need access to the app-state DataStore.
 *
 * Use [dagger.hilt.android.EntryPointAccessors.fromApplication].
 *
 * Prefer constructor injection with [@AppStateDataStore] for Hilt classes — this
 * entry-point is for static-context call-sites only (e.g. [InsetAwareConstraintLayout]
 * inflated from XML, top-level [FeatureGrayManager] object).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppStateDataStoreEntryPoint {
    @AppStateDataStore
    fun appStateDataStore(): DataStore<Preferences>
}
