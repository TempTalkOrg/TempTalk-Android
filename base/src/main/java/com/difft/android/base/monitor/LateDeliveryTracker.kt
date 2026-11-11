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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import util.AppForegroundObserver
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton


object LateDeliveryRule {
    
    const val LATE_AFTER_MS = 5 * 60_000L

    



    const val MAX_LATENCY_MS = 24 * 60 * 60_000L

    
    const val CATCH_UP_WINDOW_MS = 120_000L

    




    const val MIN_DAYS = 2
    const val WINDOW_DAYS = 7
}













@Singleton
class LateDeliveryTracker @Inject constructor(
    @param:AppStateDataStore private val dataStore: DataStore<Preferences>,
    private val healthCounters: KeepAliveHealthCounters,
    private val network: ValidatedNetworkTracker,
) {

    internal var serverNow: () -> Long? = { if (ServerTimeProvider.isAnchored()) ServerTimeProvider.nowMillis() else null }
    internal var isForeground: () -> Boolean = { AppForegroundObserver.isForegrounded() }
    internal var elapsedClock: () -> Long = { SystemClock.elapsedRealtime() }
    internal var wallClock: () -> Long = { System.currentTimeMillis() }

    
    internal var zoneOffsetMs: (Long) -> Long = { TimeZone.getDefault().getOffset(it).toLong() }

    private fun localWall(): Long = wallClock().let { it + zoneOffsetMs(it) }
    internal var onlineMsWithin: (Long) -> Long? = { network.onlineMsWithin(it) }
    internal var scheduleFlush: () -> Unit = { appScope.launch { flushPending() } }

    @Volatile
    internal var lastForegroundElapsed: Long? = null

    private val pending = AtomicInteger(0)
    private val flushing = AtomicBoolean(false)

    init {
        runCatching {
            
            if (AppForegroundObserver.isForegrounded()) lastForegroundElapsed = elapsedClock()
            AppForegroundObserver.addListener(object : AppForegroundObserver.Listener {
                override fun onForeground() {
                    lastForegroundElapsed = elapsedClock()
                }
            })
        }
    }

    




    fun onEnvelopeDelivered(serverTimestamp: Long) {
        if (serverTimestamp <= 0L) return
        val now = serverNow() ?: return
        val latency = now - serverTimestamp
        if (latency <= LateDeliveryRule.LATE_AFTER_MS || latency > LateDeliveryRule.MAX_LATENCY_MS) return
        if (!isBackgroundOrCatchUp()) return
        
        val lateOnline = onlineMsWithin(latency) ?: return
        if (lateOnline <= LateDeliveryRule.LATE_AFTER_MS) return
        pending.incrementAndGet()
        scheduleFlush()
        healthCounters.onLateDelivery()
    }

    
    suspend fun lateCount(): Int =
        count(dataStore.data.first()[AppStateKeys.BG_LATE_DELIVERY_DAYS], localWall()) + pending.get()

    
    suspend fun lateDays(): Int = lateDayKeys().size

    
    suspend fun lateDayKeys(): Set<Long> {
        val nowWall = localWall()
        val days = window(dataStore.data.first()[AppStateKeys.BG_LATE_DELIVERY_DAYS], nowWall).filterValues { it > 0 }.keys
        return if (pending.get() > 0) days + Math.floorDiv(nowWall, DAY_MS) else days
    }

    
    internal suspend fun flushPending() {
        if (!flushing.compareAndSet(false, true)) return
        try {
            while (true) {
                val add = pending.getAndSet(0)
                if (add <= 0) break
                runCatching {
                    val nowWall = localWall()
                    dataStore.edit { prefs ->
                        prefs[AppStateKeys.BG_LATE_DELIVERY_DAYS] = encode(merge(prefs[AppStateKeys.BG_LATE_DELIVERY_DAYS], add, nowWall))
                    }
                }.onFailure { L.w { "[LateDelivery] flush failed error=${it.javaClass.simpleName}" } }
            }
        } finally {
            flushing.set(false)
        }
        if (pending.get() > 0) scheduleFlush()
    }

    private fun isBackgroundOrCatchUp(): Boolean {
        if (!isForeground()) return true
        val since = lastForegroundElapsed ?: return false
        return elapsedClock() - since in 0..LateDeliveryRule.CATCH_UP_WINDOW_MS
    }

    internal companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        
        fun window(raw: String?, nowWall: Long): Map<Long, Int> {
            val today = Math.floorDiv(nowWall, DAY_MS)
            val days = KeepAliveHealthCodec.decode(raw).mapNotNull { (key, value) ->
                if (!key.startsWith("d")) null else key.substring(1).toLongOrNull()?.let { it to value }
            }.toMap()
            if (days.keys.any { it > today }) return emptyMap()
            return days.filterKeys { it >= today - (LateDeliveryRule.WINDOW_DAYS - 1) }
        }

        fun count(raw: String?, nowWall: Long): Int = window(raw, nowWall).values.sum()

        fun merge(raw: String?, add: Int, nowWall: Long): Map<Long, Int> {
            val today = Math.floorDiv(nowWall, DAY_MS)
            val kept = window(raw, nowWall)
            return kept + (today to (kept[today] ?: 0) + add)
        }

        fun encode(days: Map<Long, Int>): String = KeepAliveHealthCodec.encode(days.mapKeys { "d${it.key}" })
    }
}
