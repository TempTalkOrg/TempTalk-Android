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

/**
 * #1328: how long FCM token fetch / server binding has been failing, for the daily health report
 * (`fcm_state`, `fcm_fail_h`). Record-only; it never changes push behaviour.
 *
 * Every token fetch + bind is one attempt that takes a process-local, monotonically increasing
 * generation from [beginAttempt]. A success clears the failure run and raises `lastOkGen`; a failure
 * only opens a run when its generation is newer than `lastOkGen`, so an older attempt finishing late
 * cannot reopen an interval a newer success already closed. Ordering never reads the wall clock, so
 * a user changing the time cannot invert it; in-flight attempts die with the process, so the
 * generation needs no persistence. The wall clock only measures the duration.
 */
@Singleton
class FcmTokenHealthRecorder @Inject constructor(
    @param:AppStateDataStore
    private val appStateDataStore: DataStore<Preferences>
) {

    private val generation = AtomicLong(0L)

    // Read and written only inside DataStore edits, which are serialised.
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

    /** No usable Play Services: a different cause than registration failing, so no failure duration. */
    suspend fun recordNoGms() = update("nogms") { prefs ->
        prefs[AppStateKeys.BG_FCM_STATE] = KeepAliveHealthModel.FCM_STATE_NOGMS
    }

    private suspend fun update(event: String, block: (MutablePreferences) -> Unit) {
        runCatching { appStateDataStore.edit { block(it) } }
            .onFailure { L.w { "[KeepAliveHealth] fcm token $event record failed: ${it.stackTraceToString()}" } }
    }
}
