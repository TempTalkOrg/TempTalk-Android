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
import com.google.firebase.crashlytics.FirebaseCrashlytics
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
 * Fail-safe: a monitoring probe must never break or slow startup, so each step (custom key,
 * eligibility check, exit scan, health report) runs in its own [runCatching]. All point-in-time
 * facts go in the exception message payload, never in a global custom key (which would be
 * overwritten and racy).
 */
@Singleton
class ProcessExitProbe @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    @param:AppStateDataStore
    private val appStateDataStore: DataStore<Preferences>,
    private val userManager: UserManager,
    private val healthReporter: KeepAliveHealthReporter
) {

    /**
     * Probe entry point, invoked once per cold start from a startup task. Bounded: a handful of
     * DataStore reads and writes (sample slot, exit marker, health window). `suspend`, so they run
     * directly on the caller's coroutine — the AppStartup `addNonBlocking` task already runs on
     * [kotlinx.coroutines.Dispatchers.IO].
     */
    suspend fun probe() {
        var keepAliveEnabled: Boolean? = null
        var newExits: List<ExitSample> = emptyList()
        runCatching {
            // D3: seed the persisted keep-alive flag into the stable Crashlytics custom key.
            // Only write when warmUp is ready (getUserData() non-null) — a missing key is better
            // than a misleading `false`; MessageServiceManager sets it authoritatively on the next
            // service lifecycle event.
            userManager.getUserData()?.let {
                keepAliveEnabled = it.keepAliveEnabled
                FirebaseCrashlytics.getInstance()
                    .setCustomKey(AppStateKeys.KEEP_ALIVE_ENABLED.name, it.keepAliveEnabled)
            }
        }.onFailure {
            L.w { "[ProcessExit] probe failed: ${it.stackTraceToString()}" }
        }

        // Exit samples feed the health report, so collect them for every reporting install (keep-alive
        // on or sampled control group). With an unknown keep-alive state (user data not ready) collect
        // too: the reporter stashes them and a later DISABLED decision drops them. A failing
        // eligibility check falls back to the keep-alive flag and never skips the #971 scan below.
        val enabled = keepAliveEnabled
        val collectSamples = enabled == null ||
            runCatching { healthReporter.isReporting(enabled) }.getOrDefault(enabled)
        runCatching {
            newExits = scanExits(collectSamples)
        }.onFailure {
            L.w { "[ProcessExit] exit scan failed: ${it.stackTraceToString()}" }
        }

        // #1328: aggregate + daily report. Fail-safe on its own, and still runs on launches where
        // the exit scan found nothing (the report is the denominator).
        healthReporter.onProbe(keepAliveEnabled, newExits)
    }

    /**
     * #1328: foreground hook so a device whose background connection survives for days (rare cold
     * starts) still sends its daily report; exits are only scanned at cold start.
     */
    suspend fun reportHealthIfDue() {
        runCatching { healthReporter.onProbe(userManager.getUserData()?.keepAliveEnabled, emptyList()) }
            .onFailure { L.w { "[KeepAliveHealth] foreground check failed: ${it.stackTraceToString()}" } }
    }

    /**
     * D4 scan: reports freeze-like exits (#971) and returns every new exit as an [ExitSample] when
     * [collectSamples] is set (#1328). Advances the dedup marker past everything examined.
     */
    private suspend fun scanExits(collectSamples: Boolean): List<ExitSample> {
        val samples = ArrayList<ExitSample>()
        // D4: exit reasons require API 30+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return samples

        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return samples
        // maxNum=0 means "all available records" (the system retains ~16). Heavy-background
        // users can accumulate >10 exits between launches, so a fixed cap would undercount.
        val exits = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
        if (exits.isEmpty()) return samples

        val storedMarker: Long? = appStateDataStore.data.first()[AppStateKeys.LAST_SEEN_EXIT_TS]

        if (storedMarker == null) {
            // First probe ever on this install (or after a data clear). Reporting historical
            // exits now would surface stale pre-install kills as fresh non-fatals, so just seed
            // the marker to the newest known exit and report nothing this time.
            val newest = exits.maxOfOrNull { it.timestamp } ?: 0L
            runCatching {
                appStateDataStore.edit { it[AppStateKeys.LAST_SEEN_EXIT_TS] = newest }
            }.onFailure { L.w { "[ProcessExit] dedup marker seed failed: ${it.stackTraceToString()}" } }
            return samples
        }

        // Advance the dedup marker past EVERY exit we examine (not just reported ones), so a
        // newer non-freeze exit still moves the marker forward and nothing is ever re-scanned.
        val marker: Long = storedMarker
        var newestSeen = marker
        exits.forEach { info ->
            if (info.timestamp <= marker) return@forEach
            if (info.timestamp > newestSeen) newestSeen = info.timestamp
            if (collectSamples) samples.add(KeepAliveHealthModel.toSample(info.reason, info.importance))
            val isFreezeLike = info.reason == ApplicationExitInfo.REASON_FREEZER ||
                info.reason == ApplicationExitInfo.REASON_ANR ||
                info.reason == ApplicationExitInfo.REASON_LOW_MEMORY
            if (!isFreezeLike) return@forEach

            FirebaseCrashlytics.getInstance().recordException(
                RuntimeException(
                    "[ProcessExit] reason=${exitReasonName(info.reason)} ts=${info.timestamp} " +
                        "desc=${info.description} importance=${info.importance}"
                )
            )
        }

        // Persist the marker so each exit is reported exactly once. Isolated runCatching: a
        // write failure must be visible on its own (not conflated with the probe), and if it
        // does fail the only consequence is a re-report next launch — never a crash.
        if (newestSeen > marker) {
            runCatching {
                appStateDataStore.edit { it[AppStateKeys.LAST_SEEN_EXIT_TS] = newestSeen }
            }.onFailure { L.w { "[ProcessExit] dedup marker persist failed: ${it.stackTraceToString()}" } }
        }
        return samples
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        else -> reason.toString()
    }
}
