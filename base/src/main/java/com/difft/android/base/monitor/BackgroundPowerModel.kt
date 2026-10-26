package com.difft.android.base.monitor

/**
 * #1376: one reading of the device and process state, taken on a keep-alive loop tick. Fields
 * the platform could not supply are null.
 */
data class PowerSample(
    val elapsedMs: Long,
    val uptimeMs: Long,
    /** `Process.getElapsedCpuTime`: this process's CPU time. */
    val cpuMs: Long,
    val foreground: Boolean,
    val charging: Boolean?,
    val transport: PowerTransport,
    /** `TrafficStats` rx + tx bytes of this uid. */
    val uidBytes: Long?,
    /** `BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER`, µAh. */
    val chargeUah: Long?,
    /** `BatteryManager.BATTERY_PROPERTY_CAPACITY`, percent. */
    val capacityPct: Int?,
)

enum class PowerTransport(val key: String) { WIFI("wifi"), CELLULAR("cell"), OTHER("other"), NONE("none") }

/**
 * Background power counters for the daily health report. Every interval between two samples that
 * were both taken in the background is attributed to the charging state at its start, so
 * `pw_up_s_bat / pw_el_s_bat` is the share of on-battery background time the CPU was awake.
 */
object BackgroundPowerModel {
    const val EL_S_BAT = "pw_el_s_bat"
    const val UP_S_BAT = "pw_up_s_bat"
    const val EL_S_CHG = "pw_el_s_chg"
    const val UP_S_CHG = "pw_up_s_chg"
    const val CPU_MS_BAT = "pw_cpu_ms_bat"
    const val NET_KB_BAT = "pw_net_kb_bat"
    const val BATT_MAH = "pw_batt_mah"
    const val BATT_PCT = "pw_batt_pct"

    /** Background seconds per active transport, any charging state. */
    fun transportKey(transport: PowerTransport): String = "pw_net_${transport.key}_s"

    /** Background loop events ([PowerEvent]) counted while in the background. */
    fun eventKey(event: PowerEvent): String = "pw_${event.key}"

    /** Bucket for how long a WebSocket envelope waited in the batch queue before processing. */
    fun queueWaitEvent(queuedMs: Long): PowerEvent = when {
        queuedMs <= 2_000L -> PowerEvent.QUEUE_WAIT_2S
        queuedMs <= 10_000L -> PowerEvent.QUEUE_WAIT_10S
        else -> PowerEvent.QUEUE_WAIT_MORE
    }

    fun isPowerKey(key: String): Boolean = key.startsWith("pw_")

    /** Intervals longer than this (process frozen, clock jump) are not attributed. */
    internal const val MAX_INTERVAL_MS = 30 * 60 * 1000L

    /** Report order of the counters following `pw_awake_bat`. */
    val REPORT_KEYS: List<String> = listOf(EL_S_BAT, UP_S_BAT, EL_S_CHG, UP_S_CHG, CPU_MS_BAT, NET_KB_BAT, BATT_MAH, BATT_PCT) +
        PowerTransport.entries.map { transportKey(it) } +
        PowerEvent.entries.map { eventKey(it) }

    /**
     * Raw per-interval amounts in fine units (ms, bytes, µAh, percent), keyed by report key; pass
     * them through [carry] before counting so sub-unit amounts are not lost on every interval.
     */
    fun delta(prev: PowerSample, now: PowerSample): Map<String, Long> {
        if (prev.foreground || now.foreground) return emptyMap()
        val elapsed = now.elapsedMs - prev.elapsedMs
        if (elapsed <= 0L || elapsed > MAX_INTERVAL_MS) return emptyMap()
        val uptime = (now.uptimeMs - prev.uptimeMs).coerceIn(0L, elapsed)
        val out = HashMap<String, Long>()
        fun add(key: String, value: Long) {
            if (value > 0L) out[key] = value
        }
        when (prev.charging) {
            false -> { add(EL_S_BAT, elapsed); add(UP_S_BAT, uptime) }
            true -> { add(EL_S_CHG, elapsed); add(UP_S_CHG, uptime) }
            null -> Unit
        }
        add(transportKey(prev.transport), elapsed)
        if (prev.charging == false) {
            add(CPU_MS_BAT, now.cpuMs - prev.cpuMs)
            if (prev.uidBytes != null && now.uidBytes != null) add(NET_KB_BAT, now.uidBytes - prev.uidBytes)
            if (prev.chargeUah != null && now.chargeUah != null) add(BATT_MAH, prev.chargeUah - now.chargeUah)
            if (prev.capacityPct != null && now.capacityPct != null) add(BATT_PCT, (prev.capacityPct - now.capacityPct).toLong())
        }
        return out
    }

    /**
     * Converts fine-unit [amounts] to whole report units, keeping each key's remainder in
     * [residue] for the next interval.
     */
    fun carry(residue: MutableMap<String, Long>, amounts: Map<String, Long>): Map<String, Int> {
        val out = HashMap<String, Int>()
        amounts.forEach { (key, value) ->
            val unit = unitOf(key)
            val total = (residue[key] ?: 0L) + value
            val whole = total / unit
            residue[key] = total - whole * unit
            if (whole > 0L) out[key] = whole.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return out
    }

    private fun unitOf(key: String): Long = when (key) {
        CPU_MS_BAT, BATT_PCT -> 1L
        NET_KB_BAT -> 1024L
        else -> 1000L // ms → s for time keys, µAh → mAh
    }

    /** ` pw_awake_bat=<pct|na>` followed by every counter in [REPORT_KEYS] order, zeros included. */
    fun formatSection(events: Map<String, Int>): String = buildString {
        val el = events[EL_S_BAT] ?: 0
        append(" pw_awake_bat=").append(if (el > 0) ((events[UP_S_BAT] ?: 0).toLong() * 100 / el).toString() else "na")
        REPORT_KEYS.forEach { append(' ').append(it).append('=').append(events[it] ?: 0) }
    }
}

/** Keep-alive loop and message queue events counted in the background, one counter each. */
enum class PowerEvent(val key: String) {
    /** Wake by the keep-alive elapsed-realtime alarm. */
    WAKE_KEEP_ALIVE("wake_ka"),

    /** Wake by the 5-minute service check alarm. */
    WAKE_SERVICE("wake_svc"),
    KEEP_ALIVE_SENT("ka_sent"),

    /** Socket dropped for a missed keep-alive response. */
    KEEP_ALIVE_MISSED("ka_miss"),
    RECONNECTED("reconnect"),

    /** WebSocket envelope processed within 2 s, 10 s, or later, of entering the batch queue. */
    QUEUE_WAIT_2S("qwait_2s"),
    QUEUE_WAIT_10S("qwait_10s"),
    QUEUE_WAIT_MORE("qwait_more"),
}
