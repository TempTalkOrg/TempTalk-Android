package com.difft.android.base.monitor





data class PowerSample(
    val elapsedMs: Long,
    val uptimeMs: Long,
    
    val cpuMs: Long,
    val foreground: Boolean,
    val charging: Boolean?,
    val transport: PowerTransport,
    
    val uidBytes: Long?,
    
    val chargeUah: Long?,
    
    val capacityPct: Int?,
)

enum class PowerTransport(val key: String) { WIFI("wifi"), CELLULAR("cell"), OTHER("other"), NONE("none") }






object BackgroundPowerModel {
    const val EL_S_BAT = "pw_el_s_bat"
    const val UP_S_BAT = "pw_up_s_bat"
    const val EL_S_CHG = "pw_el_s_chg"
    const val UP_S_CHG = "pw_up_s_chg"
    const val CPU_MS_BAT = "pw_cpu_ms_bat"
    const val NET_KB_BAT = "pw_net_kb_bat"
    const val BATT_MAH = "pw_batt_mah"
    const val BATT_PCT = "pw_batt_pct"

    
    fun transportKey(transport: PowerTransport): String = "pw_net_${transport.key}_s"

    
    fun eventKey(event: PowerEvent): String = "pw_${event.key}"

    
    fun queueWaitEvent(queuedMs: Long): PowerEvent = when {
        queuedMs <= 2_000L -> PowerEvent.QUEUE_WAIT_2S
        queuedMs <= 10_000L -> PowerEvent.QUEUE_WAIT_10S
        else -> PowerEvent.QUEUE_WAIT_MORE
    }

    fun isPowerKey(key: String): Boolean = key.startsWith("pw_")

    
    internal const val MAX_INTERVAL_MS = 30 * 60 * 1000L

    
    val REPORT_KEYS: List<String> = listOf(EL_S_BAT, UP_S_BAT, EL_S_CHG, UP_S_CHG, CPU_MS_BAT, NET_KB_BAT, BATT_MAH, BATT_PCT) +
        PowerTransport.entries.map { transportKey(it) } +
        PowerEvent.entries.map { eventKey(it) }

    



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
        else -> 1000L 
    }

    
    fun formatSection(events: Map<String, Int>): String = buildString {
        val el = events[EL_S_BAT] ?: 0
        append(" pw_awake_bat=").append(if (el > 0) ((events[UP_S_BAT] ?: 0).toLong() * 100 / el).toString() else "na")
        REPORT_KEYS.forEach { append(' ').append(it).append('=').append(events[it] ?: 0) }
    }
}


enum class PowerEvent(val key: String) {
    
    WAKE_KEEP_ALIVE("wake_ka"),

    
    WAKE_SERVICE("wake_svc"),
    KEEP_ALIVE_SENT("ka_sent"),

    
    KEEP_ALIVE_MISSED("ka_miss"),
    RECONNECTED("reconnect"),

    
    QUEUE_WAIT_2S("qwait_2s"),
    QUEUE_WAIT_10S("qwait_10s"),
    QUEUE_WAIT_MORE("qwait_more"),
}
