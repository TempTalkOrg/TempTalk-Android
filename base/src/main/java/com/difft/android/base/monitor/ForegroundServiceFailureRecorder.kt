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

/**
 * #1328: persisted counter of keep-alive foreground-service start failures, drained into the
 * daily [KeepAliveHealthReporter] report and into the 7-day verdict window. Enum + count only.
 *
 * Never throws and never blocks the caller: [record] hands the DataStore write to `appScope`
 * (IO), so it is safe from the main thread and from inside a catch block.
 */
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
                // Window fgs_fail counts terminal failures only, so a start recovered by the retry
                // (which also counts in fgs_starts) is not read as a failure by the F rule.
                KeepAliveHealthWindow.mergeDaily(prefs, if (type.terminal) mapOf(HealthEventKeys.FGS_FAIL to 1) else emptyMap(), nowWall)
            }
        }.onFailure { L.w { "[KeepAliveHealth] fgs failure record failed type=${type.name} ${it.stackTraceToString()}" } }
    }
}
