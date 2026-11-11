package com.difft.android.base.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.storage.di.SecureConfigDataStore
import com.difft.android.base.storage.di.SecureUserDataStore
import com.difft.android.base.storage.schema.GlobalConfigData
import com.difft.android.base.storage.schema.UserAuthData
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton













@Singleton
class StoragePreloader @Inject constructor(
    @param:SecureUserDataStore private val secureUserStore: DataStore<UserAuthData>,
    @param:SecureConfigDataStore private val secureConfigStore: DataStore<GlobalConfigData>,
    @param:AppStateDataStore private val appStateStore: DataStore<Preferences>,
) {
    suspend fun preload() = coroutineScope {
        L.i { "[Storage][Preloader] warm-up start" }
        val start = System.currentTimeMillis()
        val userJob = async { secureUserStore.data.first() }
        val configJob = async { secureConfigStore.data.first() }
        val appStateJob = async { appStateStore.data.first().also(KeyboardHeightCache::seed) }
        userJob.await()
        configJob.await()
        appStateJob.await()
        L.i { "[Storage][Preloader] warm-up complete elapsedMs=${System.currentTimeMillis() - start}" }
    }
}
