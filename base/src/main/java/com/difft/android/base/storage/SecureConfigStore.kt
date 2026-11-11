package com.difft.android.base.storage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.di.SecureConfigDataStore
import com.difft.android.base.storage.schema.GlobalConfigData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton







@Singleton
class SecureConfigStore @Inject constructor(
    @param:SecureConfigDataStore private val dataStore: DataStore<GlobalConfigData>,
) {

    
    val configFlow: Flow<String> = dataStore.data
        .catch { e -> handleReadError(e, "configFlow") }
        .map { it.config }
        .distinctUntilChanged()

    
    val callServiceUrlStateV3Flow: Flow<String> = dataStore.data
        .catch { e -> handleReadError(e, "callServiceUrlStateV3Flow") }
        .map { it.callServiceUrlStateV3 }
        .distinctUntilChanged()

    suspend fun saveConfig(config: String) {
        dataStore.updateData { it.copy(config = config) }
    }

    suspend fun saveCallServiceUrlStateV3(state: String) {
        dataStore.updateData { it.copy(callServiceUrlStateV3 = state) }
    }

    
    suspend fun readPendingLogin(): String = dataStore.data
        .catch { e -> handleReadError(e, "readPendingLogin") }
        .map { it.pendingLogin }
        .first()

    suspend fun savePendingLogin(json: String) {
        dataStore.updateData { it.copy(pendingLogin = json) }
    }

    suspend fun clearPendingLogin() {
        dataStore.updateData { if (it.pendingLogin.isEmpty()) it else it.copy(pendingLogin = "") }
    }

    private suspend fun FlowCollector<GlobalConfigData>.handleReadError(
        e: Throwable,
        site: String,
    ) {
        when (e) {
            is CorruptionException -> {
                L.w { "[Storage][secure_config] $site decrypt/decode failed: ${e.javaClass.simpleName}" }
                emit(GlobalConfigData.EMPTY)
            }
            else -> throw e 
        }
    }
}
