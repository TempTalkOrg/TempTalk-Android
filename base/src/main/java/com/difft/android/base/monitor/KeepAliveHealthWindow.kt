package com.difft.android.base.monitor

import androidx.datastore.preferences.core.MutablePreferences
import com.difft.android.base.storage.AppStateKeys

/** One UTC day of verdict counters; [t0] is the wall-clock ms of the slot's first write. */
data class DailySlot(val day: Long, val t0: Long, val counts: Map<String, Int>)

/** Shadow "is push delivery slow" verdict over the 7-day window (#1328); report-only. */
data class HealthVerdict(
    /** `1`, `0`, or `na` while the window is too young to judge. */
    val value: String,
    /** Triggered rules: `L` latency, `F` foreground service, `P` pull. */
    val why: List<String>,
    /** Day slots in the window. */
    val days: Int,
    /** Background-arrival latency samples, all channels. */
    val samples: Int,
    val fcmSamples: Int,
    /** Share of FCM-channel samples slower than 30 min, whole percent; null without samples. */
    val fcmSlowPercent: Int?,
    /** High-priority FCM transit samples; diagnostic only, never part of [samples] or a rule. */
    val fcmTransitSamples: Int = 0,
    /** Share of FCM transit samples slower than 30 min, whole percent; null without samples. */
    val fcmTransitSlowPercent: Int? = null,
    /** App stalls longer than 2 h; diagnostic only. */
    val stallsOver2h: Int = 0
) {
    companion object {
        val NA = HealthVerdict("na", emptyList(), 0, 0, 0, null)
    }
}

/**
 * 7-day daily-slot window behind the local verdict. Every write to it goes through [mergeEvents] /
 * [mergeDaily], which first normalise the window against the caller's wall clock, so counters flush,
 * the reporter drain and the foreground-service failure recorder cannot diverge.
 *
 * Day = `floor(wallMs / 24h)` (UTC). A slot is dropped once older than 6 days; a slot dated in the
 * future (clock moved back) empties the whole window, which re-accumulates (fewer verdicts, never
 * wrong ones). The observation start is the minimum slot [DailySlot.t0], so it only moves forward
 * when old slots are pruned and is never earlier than a real observation.
 */
object KeepAliveHealthWindow {

    const val DAY_MS = 24L * 60 * 60 * 1000
    const val WINDOW_DAYS = 7

    /** Signal's `minimumEventAgeMs`: no verdict before this much real observation time. */
    const val MIN_OBSERVATION_MS = 3 * DAY_MS

    const val MIN_LATENCY_SAMPLES = 50
    private const val SLOW_SHARE_PERCENT = 50
    private const val VERY_SLOW_SHARE_PERCENT = 10
    private const val MIN_FGS_ATTEMPTS = 10
    private const val FGS_FAIL_SHARE_PERCENT = 50
    private const val MIN_PULL_FAILS = 5

    /**
     * Counter keys kept per day: 18 latency buckets, 6 FCM transit buckets, 4 stall counters, FCM/pull
     * outcomes and foreground-service outcomes.
     */
    val DAILY_KEYS: Set<String> = (
        HealthEventKeys.ALL_LATENCY_KEYS + HealthEventKeys.ALL_FCM_TRANSIT_KEYS + HealthEventKeys.ALL_STALL_KEYS + listOf(
            HealthEventKeys.FCM_RECV, HealthEventKeys.FCM_DEPRIO,
            HealthEventKeys.PULL_OK, HealthEventKeys.PULL_FAIL,
            HealthEventKeys.FGS_STARTS, HealthEventKeys.FGS_FAIL
        )
        ).toSet()

    fun dayIndex(nowWall: Long): Long = Math.floorDiv(nowWall, DAY_MS)

    /** Drops slots older than the window; any future-dated slot empties it. */
    fun normalize(slots: List<DailySlot>, nowWall: Long): List<DailySlot> {
        val today = dayIndex(nowWall)
        if (slots.any { it.day > today }) return emptyList()
        return slots.filter { it.day >= today - (WINDOW_DAYS - 1) }
    }

    /** Adds the window-relevant part of [add] to today's slot (created with `t0 = nowWall`). */
    fun mergeSlots(slots: List<DailySlot>, add: Map<String, Int>, nowWall: Long): List<DailySlot> {
        val kept = normalize(slots, nowWall)
        val daily = add.filter { it.key in DAILY_KEYS && it.value > 0 }
        if (daily.isEmpty()) return kept
        val today = dayIndex(nowWall)
        val current = kept.firstOrNull { it.day == today }
        val updated = DailySlot(today, current?.t0 ?: nowWall, KeepAliveHealthModel.merge(current?.counts ?: emptyMap(), daily))
        return (kept.filter { it.day != today } + updated).sortedBy { it.day }
    }

    fun windowStart(slots: List<DailySlot>): Long? = slots.minOfOrNull { it.t0 }

    fun readSlots(prefs: MutablePreferences): List<DailySlot> = decode(prefs[AppStateKeys.BG_HEALTH_DAILY])

    private fun writeSlots(prefs: MutablePreferences, slots: List<DailySlot>) {
        if (slots.isEmpty()) prefs.remove(AppStateKeys.BG_HEALTH_DAILY) else prefs[AppStateKeys.BG_HEALTH_DAILY] = encode(slots)
    }

    /** Prunes expired slots / resets on clock rollback, persisting the result. Returns the normalised slots. */
    fun normalizeWindow(prefs: MutablePreferences, nowWall: Long): List<DailySlot> {
        val normalized = normalize(readSlots(prefs), nowWall)
        writeSlots(prefs, normalized)
        return normalized
    }

    /** Daily part only: used by writers whose 24 h counters live elsewhere (foreground-service failures). */
    fun mergeDaily(prefs: MutablePreferences, add: Map<String, Int>, nowWall: Long): List<DailySlot> {
        val merged = mergeSlots(readSlots(prefs), add, nowWall)
        writeSlots(prefs, merged)
        return merged
    }

    /**
     * The one merge helper for event counters: adds [add] to the 24 h report counters and to today's
     * daily slot inside the caller's single edit, normalising the window first.
     */
    fun mergeEvents(prefs: MutablePreferences, add: Map<String, Int>, nowWall: Long): List<DailySlot> {
        val events = KeepAliveHealthModel.merge(KeepAliveHealthCodec.decode(prefs[AppStateKeys.BG_HEALTH_EVENT_COUNTS]), add)
        if (events.isEmpty()) prefs.remove(AppStateKeys.BG_HEALTH_EVENT_COUNTS)
        else prefs[AppStateKeys.BG_HEALTH_EVENT_COUNTS] = KeepAliveHealthCodec.encode(events)
        // Power counters only feed the 24 h report, not the 7-day verdict slots.
        return mergeDaily(prefs, add.filterKeys { !BackgroundPowerModel.isPowerKey(it) }, nowWall)
    }

    fun verdict(slots: List<DailySlot>, nowWall: Long): HealthVerdict {
        val sum = slots.fold(emptyMap<String, Int>()) { acc, slot -> KeepAliveHealthModel.merge(acc, slot.counts) }
        fun count(key: String) = sum[key] ?: 0
        fun channelCount(channel: DeliveryChannel) = HealthEventKeys.LATENCY_SUFFIXES.sumOf { count(HealthEventKeys.latencyKey(channel, it)) }
        fun channelSlow(channel: DeliveryChannel) =
            count(HealthEventKeys.latencyKey(channel, "2h")) + count(HealthEventKeys.latencyKey(channel, HealthEventKeys.LATENCY_MORE))

        val samples = DeliveryChannel.entries.sumOf { channelCount(it) }
        val slow = DeliveryChannel.entries.sumOf { channelSlow(it) }
        val verySlow = DeliveryChannel.entries.sumOf { count(HealthEventKeys.latencyKey(it, HealthEventKeys.LATENCY_MORE)) }
        val fcmSamples = channelCount(DeliveryChannel.FCM)
        val fcmSlowPercent = if (fcmSamples > 0) channelSlow(DeliveryChannel.FCM) * 100 / fcmSamples else null
        val transitSamples = HealthEventKeys.ALL_FCM_TRANSIT_KEYS.sumOf { count(it) }
        val transitSlow = count(HealthEventKeys.fcmTransitKey("2h")) + count(HealthEventKeys.fcmTransitKey(HealthEventKeys.LATENCY_MORE))
        val transitSlowPercent = if (transitSamples > 0) transitSlow * 100 / transitSamples else null
        fun result(value: String, why: List<String>) = HealthVerdict(
            value, why, slots.size, samples, fcmSamples, fcmSlowPercent,
            transitSamples, transitSlowPercent, count(HealthEventKeys.STALL_MORE)
        )

        val start = windowStart(slots)
        val mature = start != null && nowWall - start >= MIN_OBSERVATION_MS
        if (!mature) return result("na", emptyList())

        val why = buildList {
            if (samples >= MIN_LATENCY_SAMPLES &&
                (slow * 100 >= samples * SLOW_SHARE_PERCENT || verySlow * 100 >= samples * VERY_SLOW_SHARE_PERCENT)
            ) add("L")
            val attempts = count(HealthEventKeys.FGS_STARTS) + count(HealthEventKeys.FGS_FAIL)
            if (attempts >= MIN_FGS_ATTEMPTS && count(HealthEventKeys.FGS_FAIL) * 100 >= attempts * FGS_FAIL_SHARE_PERCENT) add("F")
            if (count(HealthEventKeys.PULL_FAIL) >= MIN_PULL_FAILS) add("P")
        }
        return result(if (why.isEmpty()) "0" else "1", why)
    }

    /** `day@t0=NAME:n,NAME:n;day@t0=...`. */
    fun encode(slots: List<DailySlot>): String =
        slots.sortedBy { it.day }.joinToString(";") { "${it.day}@${it.t0}=${KeepAliveHealthCodec.encode(it.counts)}" }

    /** Tolerates corrupt input (drops bad slots); duplicate days merge (earliest `t0`). */
    fun decode(raw: String?): List<DailySlot> {
        if (raw.isNullOrEmpty()) return emptyList()
        val byDay = LinkedHashMap<Long, DailySlot>()
        raw.split(';').forEach { part ->
            val at = part.indexOf('@')
            val eq = part.indexOf('=')
            if (at <= 0 || eq <= at) return@forEach
            val day = part.substring(0, at).toLongOrNull() ?: return@forEach
            val t0 = part.substring(at + 1, eq).toLongOrNull() ?: return@forEach
            val counts = KeepAliveHealthCodec.decode(part.substring(eq + 1))
            val prev = byDay[day]
            byDay[day] = if (prev == null) DailySlot(day, t0, counts)
            else DailySlot(day, minOf(prev.t0, t0), KeepAliveHealthModel.merge(prev.counts, counts))
        }
        return byDay.values.sortedBy { it.day }
    }
}
