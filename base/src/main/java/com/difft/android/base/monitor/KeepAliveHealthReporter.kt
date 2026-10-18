package com.difft.android.base.monitor

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Single exception type for the daily health report so every report groups into one Crashlytics issue. */
class BackgroundConnectionHealthReport(message: String) : RuntimeException(message)

/**
 * #1328: measure how often the OEM kills the background connection, per brand/model (Crashlytics
 * supplies the device dimensions). Firebase Analytics is unreachable from mainland China without a
 * proxy, so the data rides on one Crashlytics non-fatal per 24 h per keep-alive user, plus a
 * [KeepAliveHealthModel.CONTROL_SAMPLE_PERCENT] sample of keep-alive-off users as the FCM-only
 * control group.
 *
 * Called by [ProcessExitProbe] at cold start with the exits newly seen on that launch, and again on
 * app foreground (`ProcessExitProbe.reportHealthIfDue`) so long-lived processes still report. Exits,
 * FGS start failures and [KeepAliveHealthCounters] events accumulate in app_state across throttled
 * launches; the report goes out when the window reaches [KeepAliveHealthModel.REPORT_INTERVAL_MS],
 * even if all counts are zero (the denominator). Fail-safe: [onProbe] and [process] never throw.
 */
@Singleton
class KeepAliveHealthReporter @Inject constructor(
    @param:ApplicationContext
    private val context: Context,
    @param:AppStateDataStore
    private val appStateDataStore: DataStore<Preferences>,
    private val counters: KeepAliveHealthCounters
) {

    // Cold-start probe and foreground hook can overlap; one decision at a time avoids a double report.
    private val mutex = Mutex()

    /**
     * @param keepAliveEnabled null when the user data is not ready yet: no decision is made, but
     *   [newExits] are stashed in the pending exit counts (the marker already moved past them). A
     *   later DISABLED decision clears them, so non-reporting installs drop them.
     */
    suspend fun onProbe(keepAliveEnabled: Boolean?, newExits: List<ExitSample>) {
        if (keepAliveEnabled == null) {
            if (newExits.isNotEmpty()) mutex.withLock { stashExits(newExits) }
            return
        }
        mutex.withLock {
            process(
                keepAliveEnabled = keepAliveEnabled,
                newExits = newExits,
                now = System.currentTimeMillis(),
                snapshotProvider = { collectSnapshot(keepAliveEnabled) },
                sink = { message -> FirebaseCrashlytics.getInstance().recordException(BackgroundConnectionHealthReport(message)) }
            )
        }
    }

    /**
     * Whether this install reports (keep-alive on, or sampled into the control group), drawing and
     * persisting the stable sample slot on first use. Lets [ProcessExitProbe] collect exits for
     * control-group installs too. Falls back to [keepAliveEnabled] if app_state is unreadable.
     */
    suspend fun isReporting(keepAliveEnabled: Boolean, newSampleSlot: () -> Int = { Random.nextInt(100) }): Boolean =
        runCatching {
            var slot = appStateDataStore.data.first()[AppStateKeys.BG_HEALTH_SAMPLE_SLOT]
            if (slot == null) {
                appStateDataStore.edit { prefs ->
                    slot = prefs[AppStateKeys.BG_HEALTH_SAMPLE_SLOT] ?: newSampleSlot().also { prefs[AppStateKeys.BG_HEALTH_SAMPLE_SLOT] = it }
                }
            }
            KeepAliveHealthModel.isEligible(keepAliveEnabled, slot ?: 0)
        }.getOrDefault(keepAliveEnabled)

    internal suspend fun process(
        keepAliveEnabled: Boolean,
        newExits: List<ExitSample>,
        now: Long,
        snapshotProvider: () -> HealthSnapshot,
        sink: (String) -> Unit,
        newSampleSlot: () -> Int = { Random.nextInt(100) }
    ) {
        runCatching {
            var decision = ReportDecision.WAIT
            var lastTs: Long? = null
            var exitCounts: Map<String, Int> = emptyMap()
            var fgsFails: Map<String, Int> = emptyMap()
            var events: Map<String, Int> = emptyMap()
            var verdict = HealthVerdict.NA
            var fcmState = KeepAliveHealthModel.FCM_STATE_UNKNOWN
            var fcmFailHours = 0L
            val drained = counters.drain()

            appStateDataStore.edit { prefs ->
                val slot = prefs[AppStateKeys.BG_HEALTH_SAMPLE_SLOT]
                    ?: newSampleSlot().also { prefs[AppStateKeys.BG_HEALTH_SAMPLE_SLOT] = it }
                lastTs = prefs[AppStateKeys.BG_HEALTH_LAST_REPORT_TS]
                decision = KeepAliveHealthModel.decide(KeepAliveHealthModel.isEligible(keepAliveEnabled, slot), lastTs, now)
                when (decision) {
                    ReportDecision.DISABLED -> clearWindow(prefs, clearTs = true)
                    ReportDecision.SEED -> {
                        clearWindow(prefs, clearTs = false)
                        KeepAliveHealthWindow.normalizeWindow(prefs, now)
                        prefs[AppStateKeys.BG_HEALTH_LAST_REPORT_TS] = now
                    }
                    ReportDecision.WAIT, ReportDecision.REPORT -> {
                        exitCounts = KeepAliveHealthModel.merge(
                            KeepAliveHealthCodec.decode(prefs[AppStateKeys.BG_HEALTH_EXIT_COUNTS]),
                            KeepAliveHealthModel.aggregate(newExits)
                        )
                        fgsFails = KeepAliveHealthCodec.decode(prefs[AppStateKeys.BG_HEALTH_FGS_FAILS])
                        putOrRemove(prefs, AppStateKeys.BG_HEALTH_EXIT_COUNTS, exitCounts)
                        // Also normalises the window (prune / rollback reset), even with nothing to add.
                        val slots = KeepAliveHealthWindow.mergeEvents(prefs, drained, now)
                        events = KeepAliveHealthCodec.decode(prefs[AppStateKeys.BG_HEALTH_EVENT_COUNTS])
                        verdict = KeepAliveHealthWindow.verdict(slots, now)
                        fcmState = KeepAliveHealthModel.fcmStateName(prefs[AppStateKeys.BG_FCM_STATE])
                        val failSince = prefs[AppStateKeys.BG_FCM_FAIL_SINCE]
                        if (fcmState == KeepAliveHealthModel.FCM_STATE_FAIL && failSince != null) {
                            fcmFailHours = (now - failSince).coerceAtLeast(0L) / 3_600_000L
                        }
                    }
                }
            }
            counters.collecting = decision != ReportDecision.DISABLED
            if (decision == ReportDecision.DISABLED) counters.discard()
            if (decision != ReportDecision.REPORT) return@runCatching

            val windowMs = now - (lastTs ?: now)
            val message = KeepAliveHealthModel.formatReport(
                snapshotProvider(), windowMs, exitCounts, fgsFails, events, verdict, fcmState, fcmFailHours
            )
            sink(message)
            L.i { "[KeepAliveHealth] report $message" }

            // Clear only after the record call returned. Writes landing between the read above and this
            // edit are lost: at most one FGS failure count and one counter flush batch, acceptable.
            appStateDataStore.edit { prefs ->
                clearWindow(prefs, clearTs = false)
                prefs[AppStateKeys.BG_HEALTH_LAST_REPORT_TS] = now
            }
        }.onFailure { L.w { "[KeepAliveHealth] report failed: ${it.stackTraceToString()}" } }
    }

    private suspend fun stashExits(newExits: List<ExitSample>) {
        runCatching {
            appStateDataStore.edit { prefs ->
                putOrRemove(
                    prefs, AppStateKeys.BG_HEALTH_EXIT_COUNTS,
                    KeepAliveHealthModel.merge(
                        KeepAliveHealthCodec.decode(prefs[AppStateKeys.BG_HEALTH_EXIT_COUNTS]),
                        KeepAliveHealthModel.aggregate(newExits)
                    )
                )
            }
        }.onFailure { L.w { "[KeepAliveHealth] exit stash failed: ${it.stackTraceToString()}" } }
    }

    private fun putOrRemove(prefs: MutablePreferences, key: Preferences.Key<String>, counts: Map<String, Int>) {
        if (counts.isEmpty()) prefs.remove(key) else prefs[key] = KeepAliveHealthCodec.encode(counts)
    }

    private fun clearWindow(prefs: MutablePreferences, clearTs: Boolean) {
        prefs.remove(AppStateKeys.BG_HEALTH_EXIT_COUNTS)
        prefs.remove(AppStateKeys.BG_HEALTH_FGS_FAILS)
        prefs.remove(AppStateKeys.BG_HEALTH_EVENT_COUNTS)
        if (clearTs) {
            prefs.remove(AppStateKeys.BG_HEALTH_LAST_REPORT_TS)
            prefs.remove(AppStateKeys.BG_HEALTH_DAILY)
        }
    }

    /** Every probe is isolated: one unreadable system service must not blank the rest of the snapshot. */
    private fun collectSnapshot(keepAliveEnabled: Boolean): HealthSnapshot {
        val sdk = Build.VERSION.SDK_INT
        return HealthSnapshot(
            sdkInt = sdk,
            keepAliveEnabled = keepAliveEnabled,
            ignoringBatteryOptimizations = runCatching {
                (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(context.packageName)
            }.getOrNull(),
            backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
            }.getOrNull() else null,
            restrictBackgroundStatus = runCatching {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).restrictBackgroundStatus
            }.getOrNull(),
            notificationsEnabled = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrNull(),
            canUseFullScreenIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) runCatching {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
            }.getOrNull() else null,
            standbyBucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching {
                (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket
            }.getOrNull() else null
        )
    }
}
