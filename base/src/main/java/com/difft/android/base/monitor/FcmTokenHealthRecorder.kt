package com.difft.android.base.monitor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton












@Singleton
class FcmTokenHealthRecorder @Inject constructor(
    @param:AppStateDataStore
    private val appStateDataStore: DataStore<Preferences>
) {

    private val generation = AtomicLong(0L)

    
    private var lastOkGen = 0L

    fun beginAttempt(): Long = generation.incrementAndGet()

    suspend fun recordSuccess(gen: Long) = update("success") { prefs ->
        lastOkGen = maxOf(lastOkGen, gen)
        prefs.remove(AppStateKeys.BG_FCM_FAIL_SINCE)
        prefs[AppStateKeys.BG_FCM_STATE] = KeepAliveHealthModel.FCM_STATE_OK
    }

    suspend fun recordFailure(gen: Long, nowWall: Long = System.currentTimeMillis()) = update("failure") { prefs ->
        if (gen <= lastOkGen) return@update
        if (prefs[AppStateKeys.BG_FCM_FAIL_SINCE] == null) prefs[AppStateKeys.BG_FCM_FAIL_SINCE] = nowWall
        prefs[AppStateKeys.BG_FCM_STATE] = KeepAliveHealthModel.FCM_STATE_FAIL
    }

    
    suspend fun recordNoGms() = update("nogms") { prefs ->
        prefs[AppStateKeys.BG_FCM_STATE] = KeepAliveHealthModel.FCM_STATE_NOGMS
    }

    private suspend fun update(event: String, block: (MutablePreferences) -> Unit) {
        runCatching { appStateDataStore.edit { block(it) } }
            .onFailure { L.w { "[KeepAliveHealth] fcm token $event record failed: ${it.stackTraceToString()}" } }
    }
}
