package com.difft.android.base.monitor

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.user.UserManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #971 monitoring: seed the `keep_alive_enabled` Crashlytics custom key once (D3) and probe the
 * previous process's exit reasons (D4).
 *
 * A process can't observe "frozen right now" from inside a suspended state, so reading
 * [ActivityManager.getHistoricalProcessExitReasons] on the next launch is the correct mechanism
 * for surfacing OS-freeze / ANR / low-memory kills.
 *
 * Entirely fail-safe: a monitoring probe must never break or slow startup, so the whole body is
 * wrapped in [runCatching]. All point-in-time facts go in the exception message payload, never in
 * a global custom key (which would be overwritten and racy).
 */
@Singleton
class ProcessExitProbe @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    @param:AppStateDataStore
    private val appStateDataStore: DataStore<Preferences>,
    private val userManager: UserManager
) {

    /**
     * Probe entry point. Bounded and single-shot: a few DataStore reads plus one optional write,
     * invoked once per cold start from a startup task. `suspend`, so the DataStore calls run
     * directly on the caller's coroutine — the AppStartup `addNonBlocking` task already runs on
     * [kotlinx.coroutines.Dispatchers.IO].
     */
    suspend fun probe() {
        runCatching {
            // D4: exit reasons require API 30+.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@runCatching

            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return@runCatching
            // maxNum=0 means "all available records" (the system retains ~16). Heavy-background
            // users can accumulate >10 exits between launches, so a fixed cap would undercount.
            val exits = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            if (exits.isEmpty()) return@runCatching

            val storedMarker: Long? = appStateDataStore.data.first()[AppStateKeys.LAST_SEEN_EXIT_TS]

            if (storedMarker == null) {
                // First probe ever on this install (or after a data clear). Reporting historical
                // exits now would surface stale pre-install kills as fresh non-fatals, so just seed
                // the marker to the newest known exit and report nothing this time.
                val newest = exits.maxOfOrNull { it.timestamp } ?: 0L
                runCatching {
                    appStateDataStore.edit { it[AppStateKeys.LAST_SEEN_EXIT_TS] = newest }
                }.onFailure { L.w { "[ProcessExit] dedup marker seed failed: ${it.stackTraceToString()}" } }
                return@runCatching
            }

            // Advance the dedup marker past EVERY exit we examine (not just reported ones), so a
            // newer non-freeze exit still moves the marker forward and nothing is ever re-scanned.
            val marker: Long = storedMarker
            var newestSeen = marker
            exits.forEach { info ->
                if (info.timestamp <= marker) return@forEach
                if (info.timestamp > newestSeen) newestSeen = info.timestamp
                val isFreezeLike = info.reason == ApplicationExitInfo.REASON_FREEZER ||
                    info.reason == ApplicationExitInfo.REASON_ANR ||
                    info.reason == ApplicationExitInfo.REASON_LOW_MEMORY
                if (!isFreezeLike) return@forEach

                L.w {
                    "[ProcessExit] reason=${exitReasonName(info.reason)} ts=${info.timestamp} " +
                        "desc=${info.description} importance=${info.importance}"
                }
            }

            // Persist the marker so each exit is reported exactly once. Isolated runCatching: a
            // write failure must be visible on its own (not conflated with the probe), and if it
            // does fail the only consequence is a re-report next launch — never a crash.
            if (newestSeen > marker) {
                runCatching {
                    appStateDataStore.edit { it[AppStateKeys.LAST_SEEN_EXIT_TS] = newestSeen }
                }.onFailure { L.w { "[ProcessExit] dedup marker persist failed: ${it.stackTraceToString()}" } }
            }
        }.onFailure {
            L.w { "[ProcessExit] probe failed: ${it.stackTraceToString()}" }
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        else -> reason.toString()
    }
}
