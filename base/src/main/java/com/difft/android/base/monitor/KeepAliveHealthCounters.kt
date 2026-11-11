package com.difft.android.base.monitor

import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.time.ServerTimeProvider
import kotlinx.coroutines.launch
import util.AppForegroundObserver
import javax.inject.Inject
import javax.inject.Singleton















@Singleton
class KeepAliveHealthCounters @Inject constructor(
    @param:AppStateDataStore
    private val appStateDataStore: DataStore<Preferences>
) {

    companion object {
        internal const val FLUSH_INTERVAL_MS = 5 * 60 * 1000L

        




        internal const val PULL_FLUSH_FLOOR_MS = 30 * 1000L
    }

    internal var elapsedClock: () -> Long = { SystemClock.elapsedRealtime() }
    internal var serverNow: () -> Long? = { if (ServerTimeProvider.isAnchored()) ServerTimeProvider.nowMillis() else null }
    internal var isForeground: () -> Boolean = { AppForegroundObserver.isForegrounded() }
    internal var wallClock: () -> Long = { System.currentTimeMillis() }

    
    internal var offsetCorrectedNow: () -> Long = { ServerTimeProvider.nowMillis() }

    




    @Volatile
    var collecting: Boolean = true

    private val lock = Any()
    private val pending = HashMap<String, Int>()

    
    private var fgsAccruedFrom: Long? = null
    internal var lastFlushElapsed: Long? = null
    internal var lastPullFlushElapsed: Long? = null

    internal val backgroundListener = object : AppForegroundObserver.Listener {
        override fun onBackground() = flush(force = true)
    }

    init {
        runCatching { AppForegroundObserver.addListener(backgroundListener) }
    }

    









    fun onFcmReceived(deprioritized: Boolean, highPrioritySentTimeMs: Long = 0L) {
        if (!collecting) return
        synchronized(lock) {
            increment(HealthEventKeys.FCM_RECV)
            if (deprioritized) increment(HealthEventKeys.FCM_DEPRIO)
            if (highPrioritySentTimeMs > 0L) {
                val now = serverNow() ?: offsetCorrectedNow()
                increment(KeepAliveHealthModel.fcmTransitKey(now - highPrioritySentTimeMs))
            }
        }
        flush(force = false)
    }

    
    fun onAppStall(durationMs: Long) {
        if (!collecting || durationMs <= 0L) return
        synchronized(lock) {
            increment(KeepAliveHealthModel.stallKey(durationMs))
            val minutes = (durationMs / 60_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (minutes > 0) increment(HealthEventKeys.STALL_MIN, minutes)
        }
        flush(force = false)
    }

    
    fun onPullResult(success: Boolean) {
        if (!collecting) return
        synchronized(lock) { increment(if (success) HealthEventKeys.PULL_OK else HealthEventKeys.PULL_FAIL) }
        flush(force = false, pullResult = true)
    }

    






    fun onEnvelopeDelivered(serverTimestamp: Long, channel: DeliveryChannel) {
        if (!collecting) return
        synchronized(lock) {
            if (serverTimestamp <= 0L || isForeground()) return
            val now = serverNow() ?: return
            increment(KeepAliveHealthModel.latencyKey(channel, now - serverTimestamp))
        }
        flush(force = false)
    }

    



    fun onLateDelivery() {
        if (!collecting) return
        synchronized(lock) { increment(HealthEventKeys.LATE_5M) }
        flush(force = false)
    }

    fun onServiceStarted() {
        synchronized(lock) {
            if (fgsAccruedFrom != null) return
            fgsAccruedFrom = elapsedClock()
            if (collecting) increment(HealthEventKeys.FGS_STARTS)
        }
        
        flush(force = false)
    }

    fun onServiceStopped() {
        synchronized(lock) {
            accrueUptime()
            fgsAccruedFrom = null
        }
        flush(force = true)
    }

    
    fun onPowerCounts(add: Map<String, Int>) {
        if (!collecting) return
        synchronized(lock) { add.forEach { (k, v) -> increment(k, v) } }
        flush(force = false)
    }

    
    fun onAlarmTick() = flush(force = false)

    
    fun drain(): Map<String, Int> = synchronized(lock) {
        accrueUptime()
        val out = HashMap(pending)
        pending.clear()
        out
    }

    
    fun discard() {
        synchronized(lock) { pending.clear() }
    }

    private fun flush(force: Boolean, pullResult: Boolean = false) {
        val toPersist = synchronized(lock) {
            val now = elapsedClock()
            val last = lastFlushElapsed
            val lastPull = lastPullFlushElapsed
            val due = force || last == null || now - last >= FLUSH_INTERVAL_MS ||
                (pullResult && (lastPull == null || now - lastPull >= PULL_FLUSH_FLOOR_MS))
            if (!due) return
            accrueUptime()
            if (pending.isEmpty()) return
            lastFlushElapsed = now
            if (pullResult) lastPullFlushElapsed = now
            HashMap(pending).also { pending.clear() }
        }
        if (!collecting) return
        runCatching { appScope.launch { persist(toPersist) } }
    }

    internal suspend fun persist(add: Map<String, Int>) {
        runCatching {
            val nowWall = wallClock()
            appStateDataStore.edit { prefs -> KeepAliveHealthWindow.mergeEvents(prefs, add, nowWall) }
        }.onFailure { L.w { "[KeepAliveHealth] event flush failed: ${it.stackTraceToString()}" } }
    }

    
    private fun accrueUptime() {
        val from = fgsAccruedFrom ?: return
        val now = elapsedClock()
        val seconds = ((now - from) / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        if (seconds <= 0) return
        fgsAccruedFrom = from + seconds * 1000L
        if (collecting) increment(HealthEventKeys.FGS_ALIVE_S, seconds)
    }

    private fun increment(key: String, by: Int = 1) {
        pending[key] = (pending[key] ?: 0) + by
    }
}
