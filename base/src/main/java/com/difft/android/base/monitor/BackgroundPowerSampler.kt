package com.difft.android.base.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import dagger.hilt.android.qualifiers.ApplicationContext
import util.AppForegroundObserver
import javax.inject.Inject
import javax.inject.Singleton








@Singleton
class BackgroundPowerSampler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val counters: KeepAliveHealthCounters,
) {
    internal var reader: () -> PowerSample = { readSample() }
    internal var isForeground: () -> Boolean = { AppForegroundObserver.isForegrounded() }
    internal var log: (String) -> Unit = { line -> L.i { line } }

    private val lock = Any()
    private var last: PowerSample? = null
    private val hour = HashMap<String, Int>()
    private val residue = HashMap<String, Long>()
    private var hourStartElapsed: Long? = null

    fun onTick() {
        val now = runCatching { reader() }.getOrElse {
            L.w { "[KeepAliveHealth] power sample failed: ${it.message}" }
            return
        }
        val add = synchronized(lock) {
            val prev = last
            last = now
            val delta = if (prev != null) BackgroundPowerModel.carry(residue, BackgroundPowerModel.delta(prev, now)) else emptyMap()
            accumulateHour(delta, now.elapsedMs)
            delta
        }
        if (add.isNotEmpty()) counters.onPowerCounts(add)
    }

    fun onEvent(event: PowerEvent) {
        if (isForeground()) return
        val add = mapOf(BackgroundPowerModel.eventKey(event) to 1)
        synchronized(lock) { add.forEach { (k, v) -> hour[k] = (hour[k] ?: 0) + v } }
        counters.onPowerCounts(add)
    }

    
    private fun accumulateHour(delta: Map<String, Int>, nowElapsed: Long) {
        delta.forEach { (k, v) -> hour[k] = (hour[k] ?: 0) + v }
        val start = hourStartElapsed ?: nowElapsed.also { hourStartElapsed = it }
        if (nowElapsed - start < HOUR_MS) return
        if (hour.isNotEmpty()) log("[KeepAliveHealth] power hour${BackgroundPowerModel.formatSection(hour)}")
        hour.clear()
        hourStartElapsed = nowElapsed
    }

    private fun readSample(): PowerSample {
        val battery = context.getSystemService(BatteryManager::class.java)
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        return PowerSample(
            elapsedMs = SystemClock.elapsedRealtime(),
            uptimeMs = SystemClock.uptimeMillis(),
            cpuMs = Process.getElapsedCpuTime(),
            foreground = isForeground(),
            charging = battery?.let { runCatching { it.isCharging }.getOrNull() },
            transport = readTransport(),
            uidBytes = if (rx >= 0 && tx >= 0) rx + tx else null,
            chargeUah = battery?.batteryProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.toLong(),
            capacityPct = battery?.batteryProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        )
    }

    
    private fun BatteryManager.batteryProperty(id: Int): Int? =
        runCatching { getIntProperty(id) }.getOrNull()?.takeIf { it > 0 }

    private fun readTransport(): PowerTransport = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return PowerTransport.NONE
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return PowerTransport.NONE
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> PowerTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> PowerTransport.CELLULAR
            else -> PowerTransport.OTHER
        }
    }.getOrDefault(PowerTransport.NONE)

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
