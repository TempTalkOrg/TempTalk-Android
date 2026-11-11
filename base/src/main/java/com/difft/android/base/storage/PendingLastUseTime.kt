package com.difft.android.base.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton












@Singleton
class PendingLastUseTime @Inject constructor() {

    private val pendingValue = AtomicLong(AppStateDefaults.LAST_USE_TIME)

    @Volatile
    private var isDirty: Boolean = false

    
    fun record(timestamp: Long) {
        pendingValue.set(timestamp)
        isDirty = true
    }

    
    fun current(): Long = pendingValue.get()

    



    suspend fun loadInitial(dataStore: DataStore<Preferences>) {
        val persisted = dataStore.data.first()[AppStateKeys.LAST_USE_TIME] ?: AppStateDefaults.LAST_USE_TIME
        pendingValue.set(persisted)
        isDirty = false
    }

    
    suspend fun flush(dataStore: DataStore<Preferences>) {
        if (!isDirty) return
        val ts = pendingValue.get()
        isDirty = false  
        try {
            dataStore.edit { it[AppStateKeys.LAST_USE_TIME] = ts }
            L.i { "[Storage][app_state] flushed lastUseTime=$ts" }
        } catch (e: Exception) {
            isDirty = true  
            L.w { "[Storage][app_state] flush lastUseTime failed; isDirty restored: ${e.stackTraceToString()}" }
        }
    }
}
