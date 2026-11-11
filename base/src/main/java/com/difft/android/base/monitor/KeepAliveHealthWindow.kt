package com.difft.android.base.monitor

import androidx.datastore.preferences.core.MutablePreferences
import com.difft.android.base.storage.AppStateKeys
import java.util.TimeZone


data class DailySlot(val day: Long, val t0: Long, val counts: Map<String, Int>)


data class HealthVerdict(
    
    val value: String,
    
    val why: List<String>,
    
    val days: Int,
    
    val samples: Int,
    val fcmSamples: Int,
    
    val fcmSlowPercent: Int?,
    
    val fcmTransitSamples: Int = 0,
    
    val fcmTransitSlowPercent: Int? = null,
    
    val stallsOver2h: Int = 0,
    
    val lateDeliveries: Int = 0
) {
    companion object {
        val NA = HealthVerdict("na", emptyList(), 0, 0, 0, null)
    }
}











object KeepAliveHealthWindow {

    const val DAY_MS = 24L * 60 * 60 * 1000
    const val WINDOW_DAYS = 7

    
    const val MIN_OBSERVATION_MS = 3 * DAY_MS

    private const val MIN_FGS_ATTEMPTS = 10
    private const val FGS_FAIL_SHARE_PERCENT = 50
    private const val MIN_PULL_FAILS = 5

    



    val DAILY_KEYS: Set<String> = (
        HealthEventKeys.ALL_LATENCY_KEYS + HealthEventKeys.ALL_FCM_TRANSIT_KEYS + HealthEventKeys.ALL_STALL_KEYS + listOf(
            HealthEventKeys.LATE_5M, HealthEventKeys.FCM_RECV, HealthEventKeys.FCM_DEPRIO,
            HealthEventKeys.PULL_OK, HealthEventKeys.PULL_FAIL,
            HealthEventKeys.FGS_STARTS, HealthEventKeys.FGS_FAIL
        )
        ).toSet()

    
    internal var zoneOffsetMs: (Long) -> Long = { TimeZone.getDefault().getOffset(it).toLong() }

    fun dayIndex(nowWall: Long): Long = Math.floorDiv(nowWall + zoneOffsetMs(nowWall), DAY_MS)

    




    fun normalize(slots: List<DailySlot>, nowWall: Long): List<DailySlot> {
        val today = dayIndex(nowWall)
        if (slots.any { it.day > today + 1 }) return emptyList()
        return slots.filter { it.day >= today - (WINDOW_DAYS - 1) }
    }

    
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

    
    fun normalizeWindow(prefs: MutablePreferences, nowWall: Long): List<DailySlot> {
        val normalized = normalize(readSlots(prefs), nowWall)
        writeSlots(prefs, normalized)
        return normalized
    }

    
    fun mergeDaily(prefs: MutablePreferences, add: Map<String, Int>, nowWall: Long): List<DailySlot> {
        val merged = mergeSlots(readSlots(prefs), add, nowWall)
        writeSlots(prefs, merged)
        return merged
    }

    



    fun mergeEvents(prefs: MutablePreferences, add: Map<String, Int>, nowWall: Long): List<DailySlot> {
        val events = KeepAliveHealthModel.merge(KeepAliveHealthCodec.decode(prefs[AppStateKeys.BG_HEALTH_EVENT_COUNTS]), add)
        if (events.isEmpty()) prefs.remove(AppStateKeys.BG_HEALTH_EVENT_COUNTS)
        else prefs[AppStateKeys.BG_HEALTH_EVENT_COUNTS] = KeepAliveHealthCodec.encode(events)
        
        return mergeDaily(prefs, add.filterKeys { !BackgroundPowerModel.isPowerKey(it) }, nowWall)
    }

    fun verdict(slots: List<DailySlot>, nowWall: Long): HealthVerdict {
        val sum = slots.fold(emptyMap<String, Int>()) { acc, slot -> KeepAliveHealthModel.merge(acc, slot.counts) }
        fun count(key: String) = sum[key] ?: 0
        fun channelCount(channel: DeliveryChannel) = HealthEventKeys.LATENCY_SUFFIXES.sumOf { count(HealthEventKeys.latencyKey(channel, it)) }
        fun channelSlow(channel: DeliveryChannel) =
            count(HealthEventKeys.latencyKey(channel, "2h")) + count(HealthEventKeys.latencyKey(channel, HealthEventKeys.LATENCY_MORE))
        val lateDeliveries = count(HealthEventKeys.LATE_5M)
        val lateDays = slots.count { (it.counts[HealthEventKeys.LATE_5M] ?: 0) > 0 }

        val samples = DeliveryChannel.entries.sumOf { channelCount(it) }
        val fcmSamples = channelCount(DeliveryChannel.FCM)
        val fcmSlowPercent = if (fcmSamples > 0) channelSlow(DeliveryChannel.FCM) * 100 / fcmSamples else null
        val transitSamples = HealthEventKeys.ALL_FCM_TRANSIT_KEYS.sumOf { count(it) }
        val transitSlow = count(HealthEventKeys.fcmTransitKey("2h")) + count(HealthEventKeys.fcmTransitKey(HealthEventKeys.LATENCY_MORE))
        val transitSlowPercent = if (transitSamples > 0) transitSlow * 100 / transitSamples else null
        fun result(value: String, why: List<String>) = HealthVerdict(
            value, why, slots.size, samples, fcmSamples, fcmSlowPercent,
            transitSamples, transitSlowPercent, count(HealthEventKeys.STALL_MORE), lateDeliveries
        )

        val start = windowStart(slots)
        val mature = start != null && nowWall - start >= MIN_OBSERVATION_MS
        if (!mature) return result("na", emptyList())

        val why = buildList {
            if (lateDays >= LateDeliveryRule.MIN_DAYS) add("L")
            val attempts = count(HealthEventKeys.FGS_STARTS) + count(HealthEventKeys.FGS_FAIL)
            if (attempts >= MIN_FGS_ATTEMPTS && count(HealthEventKeys.FGS_FAIL) * 100 >= attempts * FGS_FAIL_SHARE_PERCENT) add("F")
            if (count(HealthEventKeys.PULL_FAIL) >= MIN_PULL_FAILS) add("P")
        }
        return result(if (why.isEmpty()) "0" else "1", why)
    }

    
    fun encode(slots: List<DailySlot>): String =
        slots.sortedBy { it.day }.joinToString(";") { "${it.day}@${it.t0}=${KeepAliveHealthCodec.encode(it.counts)}" }

    
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
