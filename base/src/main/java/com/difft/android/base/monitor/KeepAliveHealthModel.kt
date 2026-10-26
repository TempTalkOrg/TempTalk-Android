package com.difft.android.base.monitor

import android.app.ApplicationExitInfo

/**
 * Pure (Android-free at runtime) building blocks of the #1328 background-connection health report:
 * counter codec, exit aggregation, report throttle and message format. Kept separate from the
 * DataStore / system-service plumbing so every decision is unit-testable on the JVM.
 *
 * Privacy: only enum names, counts and coarse system-state flags. No identifiers.
 */

/** One historical process exit, reduced to the two facts the report needs. */
data class ExitSample(
    /** Stable reason name, see [KeepAliveHealthModel.exitReasonName]. */
    val reasonName: String,
    /** True when the process importance at exit was `<= IMPORTANCE_FOREGROUND_SERVICE`. */
    val atForegroundServiceOrAbove: Boolean
)

/** System-state facts at report time. `null` means "not available on this API level / unreadable". */
data class HealthSnapshot(
    val sdkInt: Int,
    val keepAliveEnabled: Boolean,
    val ignoringBatteryOptimizations: Boolean?,
    val backgroundRestricted: Boolean?,
    /** `ConnectivityManager.RESTRICT_BACKGROUND_STATUS_*` value. */
    val restrictBackgroundStatus: Int?,
    val notificationsEnabled: Boolean?,
    val canUseFullScreenIntent: Boolean?,
    /** `UsageStatsManager.STANDBY_BUCKET_*` value. */
    val standbyBucket: Int?,
    /** `AlarmManager.canScheduleExactAlarms`; sets the stall threshold, so it qualifies the `stall_*` counters. */
    val canScheduleExactAlarms: Boolean? = null,
    /** USB/wireless debugging on: a developer device whose power numbers adb skews (#1376). */
    val adbEnabled: Boolean? = null
)

/** What the reporter should do on this launch. */
enum class ReportDecision {
    /** Not reporting (keepAlive off and outside the control sample): drop pending data and the window start. */
    DISABLED,

    /** First eligible launch (or clock moved backwards): start a window, no report. */
    SEED,

    /** Window not yet 24 h old: keep accumulating. */
    WAIT,

    /** Send the report. */
    REPORT
}

/**
 * Failure types for [ForegroundServiceFailureRecorder]; the enum name is the persisted key.
 *
 * [terminal] marks failures after which no further start attempt follows. The direct-attempt
 * failures are always followed by a background retry that usually succeeds, so only terminal ones
 * feed the 7-day `fgs_fail` count behind the verdict's F rule; the 24 h report lists every type.
 */
enum class FgsStartFailure(val terminal: Boolean) {
    /** The direct attempt threw `UnableToStartException` (a wrapped ForegroundServiceStartNotAllowedException). Retried. */
    DIRECT_NOT_ALLOWED(terminal = false),

    /** `startForegroundService` threw SecurityException on the direct attempt. Retried. */
    DIRECT_SECURITY(terminal = false),

    /** Any other exception on the direct attempt. Retried. */
    DIRECT_OTHER(terminal = false),

    /** The deferred background retry (`startWhenCapable`) also failed. */
    RETRY_FAILED(terminal = true),

    /** The service started but `Service.startForeground` was refused (FGSNAE), then stopSelf(). */
    STARTFG_REFUSED(terminal = true),

    /** No notification channel could carry the service notification, so the start was not issued (#1354). */
    NO_CHANNEL(terminal = true)
}

/** Keys of the in-process event counters ([KeepAliveHealthCounters]); also the report field names. */
object HealthEventKeys {
    /** Seconds the keep-alive foreground service was alive in the window. Reported as minutes. */
    const val FGS_ALIVE_S = "fgs_alive_s"
    const val FGS_STARTS = "fgs_starts"
    const val FCM_RECV = "fcm_recv"

    /** FCM sent as high priority but delivered as normal (app standby / quota downgrade). */
    const val FCM_DEPRIO = "fcm_deprio"

    /** Outcome of an FCM-triggered HTTP pull ([KeepAliveHealthCounters.onPullResult]). */
    const val PULL_OK = "pull_ok"
    const val PULL_FAIL = "pull_fail"

    /** Daily-window only: foreground-service start failures of every type, see [KeepAliveHealthWindow]. */
    const val FGS_FAIL = "fgs_fail"

    /**
     * Server-to-device latency bucket upper bounds, inclusive (`latency <= bound`): `2h` plus `more`
     * is exactly "slower than 30 min" and `more` alone is exactly "slower than 2 h".
     */
    val LATENCY_BOUNDS: List<Pair<Long, String>> = listOf(
        10_000L to "10s",
        60_000L to "1m",
        5 * 60_000L to "5m",
        30 * 60_000L to "30m",
        2 * 60 * 60_000L to "2h"
    )
    const val LATENCY_MORE = "more"

    /** Bucket suffixes in report order. */
    val LATENCY_SUFFIXES: List<String> = LATENCY_BOUNDS.map { it.second } + LATENCY_MORE

    fun latencyKey(channel: DeliveryChannel, suffix: String): String = "lat_${channel.key}_$suffix"

    /** All per-channel latency keys, channel-major, in report order. */
    val ALL_LATENCY_KEYS: List<String> =
        DeliveryChannel.entries.flatMap { ch -> LATENCY_SUFFIXES.map { latencyKey(ch, it) } }

    /**
     * FCM transit latency (`receive - sentTime`) of high-priority messages, bucketed by
     * [LATENCY_BOUNDS]. Separate from the per-channel latency, which only counts the channel that
     * stored an envelope first and so hides a late FCM backlog behind the WebSocket reconnect.
     */
    fun fcmTransitKey(suffix: String): String = "fcmt_$suffix"

    /** All FCM transit keys in report order. */
    val ALL_FCM_TRANSIT_KEYS: List<String> = LATENCY_SUFFIXES.map { fcmTransitKey(it) }

    /** App stalls (alarm chain found stale) by duration: `<= 30 min`, `<= 2 h`, longer. */
    const val STALL_30M = "stall_30m"
    const val STALL_2H = "stall_2h"
    const val STALL_MORE = "stall_more"

    /** Total stalled minutes across [STALL_30M], [STALL_2H] and [STALL_MORE]. */
    const val STALL_MIN = "stall_min"

    /** All stall keys in report order. */
    val ALL_STALL_KEYS: List<String> = listOf(STALL_30M, STALL_2H, STALL_MORE, STALL_MIN)
}

/** Arrival path of an envelope; [key] is the infix of its latency counters (`lat_<key>_<bucket>`). */
enum class DeliveryChannel(val key: String) {
    /** Long-lived WebSocket (`tag == "message"`). */
    WS("ws"),

    /** FCM payload carrying the envelope. */
    FCM("fcm"),

    /** Server pull over HTTP. */
    PULL("pull")
}

object KeepAliveHealthModel {

    const val REPORT_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    /** Share of keep-alive-off devices that also report, as the FCM-only control group. */
    const val CONTROL_SAMPLE_PERCENT = 20

    /** @param sampleSlot stable per-install value in `0 until 100`. */
    fun isEligible(keepAliveEnabled: Boolean, sampleSlot: Int): Boolean =
        keepAliveEnabled || sampleSlot < CONTROL_SAMPLE_PERCENT

    /** Bucket key for a server-to-device latency; negative values (clock noise) land in the first bucket. */
    fun latencyKey(channel: DeliveryChannel, latencyMs: Long): String =
        HealthEventKeys.latencyKey(channel, latencySuffix(latencyMs))

    /** Bucket key for an FCM transit latency, same bounds and clamping as [latencyKey]. */
    fun fcmTransitKey(latencyMs: Long): String = HealthEventKeys.fcmTransitKey(latencySuffix(latencyMs))

    /** Bucket key for an app stall of [durationMs]. */
    fun stallKey(durationMs: Long): String = when {
        durationMs <= STALL_SHORT_MS -> HealthEventKeys.STALL_30M
        durationMs <= STALL_LONG_MS -> HealthEventKeys.STALL_2H
        else -> HealthEventKeys.STALL_MORE
    }

    private const val STALL_SHORT_MS = 30 * 60_000L
    private const val STALL_LONG_MS = 2 * 60 * 60_000L

    private fun latencySuffix(latencyMs: Long): String =
        HealthEventKeys.LATENCY_BOUNDS.firstOrNull { latencyMs <= it.first }?.second ?: HealthEventKeys.LATENCY_MORE

    /** `ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE`. */
    private const val IMPORTANCE_FOREGROUND_SERVICE = 125

    fun decide(eligible: Boolean, lastReportTs: Long?, now: Long): ReportDecision = when {
        !eligible -> ReportDecision.DISABLED
        lastReportTs == null || now < lastReportTs -> ReportDecision.SEED
        now - lastReportTs >= REPORT_INTERVAL_MS -> ReportDecision.REPORT
        else -> ReportDecision.WAIT
    }

    fun toSample(reason: Int, importance: Int): ExitSample =
        ExitSample(exitReasonName(reason), importance <= IMPORTANCE_FOREGROUND_SERVICE)

    /** Counter key for an exit sample, e.g. `ANR_fg`, `EXIT_SELF_bg`. */
    fun exitKey(sample: ExitSample): String =
        sample.reasonName + if (sample.atForegroundServiceOrAbove) "_fg" else "_bg"

    fun aggregate(samples: List<ExitSample>): Map<String, Int> =
        samples.groupingBy { exitKey(it) }.eachCount()

    fun merge(base: Map<String, Int>, add: Map<String, Int>): Map<String, Int> {
        val out = LinkedHashMap(base)
        add.forEach { (k, v) -> out[k] = (out[k] ?: 0) + v }
        return out
    }

    /** Names every `ApplicationExitInfo.REASON_*`; unknown values fall back to `R<number>`. */
    fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "R$reason"
    }

    /**
     * Single-line `key=value` report. Counters are flat (`exit_ANR_fg=1`) so Crashlytics message
     * search and later parsing need no nested syntax; totals are always present so an all-zero
     * report still carries the denominator. Event, verdict and FCM-token fields are always present,
     * in a fixed order.
     */
    fun formatReport(
        snapshot: HealthSnapshot,
        windowMs: Long,
        exitCounts: Map<String, Int>,
        fgsFails: Map<String, Int>,
        events: Map<String, Int> = emptyMap(),
        verdict: HealthVerdict = HealthVerdict.NA,
        fcmState: String = FCM_STATE_UNKNOWN,
        fcmFailHours: Long = 0L
    ): String = buildString {
        append("window_min=").append(windowMs.coerceAtLeast(0L) / 60_000L)
        append(" sdk=").append(snapshot.sdkInt)
        append(" keep_alive=").append(flag(snapshot.keepAliveEnabled))
        append(" ignore_batt=").append(flag(snapshot.ignoringBatteryOptimizations))
        append(" bg_restricted=").append(flag(snapshot.backgroundRestricted))
        append(" data_saver=").append(dataSaverName(snapshot.restrictBackgroundStatus))
        append(" notif=").append(flag(snapshot.notificationsEnabled))
        append(" fsi=").append(flag(snapshot.canUseFullScreenIntent))
        append(" bucket=").append(standbyBucketName(snapshot.standbyBucket))
        append(" exact_alarm=").append(flag(snapshot.canScheduleExactAlarms))
        append(" exits_total=").append(exitCounts.values.sum())
        exitCounts.toSortedMap().forEach { (k, v) -> append(" exit_").append(k).append('=').append(v) }
        append(" fgs_fail_total=").append(fgsFails.values.sum())
        fgsFails.toSortedMap().forEach { (k, v) -> append(" fgs_fail_").append(k).append('=').append(v) }
        append(" fgs_alive_min=").append((events[HealthEventKeys.FGS_ALIVE_S] ?: 0) / 60)
        (listOf(
            HealthEventKeys.FGS_STARTS, HealthEventKeys.FCM_RECV, HealthEventKeys.FCM_DEPRIO,
            HealthEventKeys.PULL_OK, HealthEventKeys.PULL_FAIL
        ) + HealthEventKeys.ALL_LATENCY_KEYS + HealthEventKeys.ALL_FCM_TRANSIT_KEYS + HealthEventKeys.ALL_STALL_KEYS).forEach {
            append(' ').append(it).append('=').append(events[it] ?: 0)
        }
        append(" pv=").append(verdict.value)
        append(" pv_why=").append(verdict.why.joinToString(",").ifEmpty { "none" })
        append(" pv_days=").append(verdict.days)
        append(" pv_n=").append(verdict.samples)
        append(" pv_fcm_n=").append(verdict.fcmSamples)
        append(" pv_fcm_slow=").append(verdict.fcmSlowPercent?.toString() ?: "na")
        append(" pv_fcmt_n=").append(verdict.fcmTransitSamples)
        append(" pv_fcmt_slow=").append(verdict.fcmTransitSlowPercent?.toString() ?: "na")
        append(" pv_stall_2h=").append(verdict.stallsOver2h)
        append(" fcm_state=").append(fcmState)
        append(" fcm_fail_h=").append(fcmFailHours.coerceAtLeast(0L))
        append(" adb=").append(flag(snapshot.adbEnabled))
        append(BackgroundPowerModel.formatSection(events))
    }

    const val FCM_STATE_OK = "ok"
    const val FCM_STATE_FAIL = "fail"
    const val FCM_STATE_NOGMS = "nogms"
    const val FCM_STATE_UNKNOWN = "unknown"

    /** Maps a persisted FCM state to a report value; anything unrecognised is `unknown`. */
    fun fcmStateName(raw: String?): String = when (raw) {
        FCM_STATE_OK, FCM_STATE_FAIL, FCM_STATE_NOGMS -> raw
        else -> FCM_STATE_UNKNOWN
    }

    private fun flag(value: Boolean?): String = when (value) {
        true -> "1"
        false -> "0"
        null -> "na"
    }

    private fun dataSaverName(status: Int?): String = when (status) {
        1 -> "off"
        2 -> "allowlisted"
        3 -> "on"
        null -> "na"
        else -> "s$status"
    }

    private fun standbyBucketName(bucket: Int?): String = when (bucket) {
        5 -> "EXEMPTED"
        10 -> "ACTIVE"
        20 -> "WORKING_SET"
        30 -> "FREQUENT"
        40 -> "RARE"
        45 -> "RESTRICTED"
        50 -> "NEVER"
        null -> "na"
        else -> "b$bucket"
    }
}

/** `NAME:count,NAME:count` codec for persisted counters. Tolerates corrupt input (drops bad pairs). */
object KeepAliveHealthCodec {

    private val KEY_PATTERN = Regex("[A-Za-z0-9_]{1,48}")

    fun encode(counts: Map<String, Int>): String =
        counts.entries.filter { it.value > 0 && KEY_PATTERN.matches(it.key) }
            .joinToString(",") { "${it.key}:${it.value}" }

    fun decode(raw: String?): Map<String, Int> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        raw.split(',').forEach { pair ->
            val idx = pair.indexOf(':')
            if (idx <= 0) return@forEach
            val key = pair.substring(0, idx)
            val value = pair.substring(idx + 1).toIntOrNull() ?: return@forEach
            if (value > 0 && KEY_PATTERN.matches(key)) out[key] = (out[key] ?: 0) + value
        }
        return out
    }
}
