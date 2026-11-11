package com.difft.android.base.monitor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton








@Singleton
class ForegroundServiceFailureRecorder @Inject constructor(
    @param:AppStateDataStore
    private val appStateDataStore: DataStore<Preferences>
) {

    fun record(type: FgsStartFailure) {
        runCatching { appScope.launch { recordNow(type) } }
    }

    internal suspend fun recordNow(type: FgsStartFailure, nowWall: Long = System.currentTimeMillis()) {
        runCatching {
            appStateDataStore.edit { prefs ->
                val counts = KeepAliveHealthCodec.decode(prefs[AppStateKeys.BG_HEALTH_FGS_FAILS])
                prefs[AppStateKeys.BG_HEALTH_FGS_FAILS] =
                    KeepAliveHealthCodec.encode(KeepAliveHealthModel.merge(counts, mapOf(type.name to 1)))
                
                
                KeepAliveHealthWindow.mergeDaily(prefs, if (type.terminal) mapOf(HealthEventKeys.FGS_FAIL to 1) else emptyMap(), nowWall)
            }
        }.onFailure { L.w { "[KeepAliveHealth] fgs failure record failed type=${type.name} ${it.stackTraceToString()}" } }
    }
}
