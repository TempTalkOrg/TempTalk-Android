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

/** What counts as a late delivery, shared by the prompt trigger and the `L` verdict rule. */
object LateDeliveryRule {
    /** A delivery is late when its server-to-device latency, minus time spent offline, exceeds this. */
    const val LATE_AFTER_MS = 5 * 60_000L

    /**
     * Longer delays are not a push-quality signal: they are an offline phone or a bulk history
     * replay (re-login, migration, first sync) and are not counted.
     */
    const val MAX_LATENCY_MS = 24 * 60 * 60_000L

    /** Deliveries received this long after the app came to the foreground are the backlog that opening it released. */
    const val CATCH_UP_WINDOW_MS = 120_000L

    /**
     * Days within [WINDOW_DAYS] with at least one late delivery that make push delivery "late" for this
     * install. Days, not deliveries: one released backlog is a single day however many messages it
     * holds, so a one-off never prompts while a problem that recurs on a second day does.
     */
    const val MIN_DAYS = 2
    const val WINDOW_DAYS = 7
}

/**
 * Local rolling count of late deliveries for every install, independent of the health-report
 * sampling. Stores per-day counts only (never content or ids), so the startup prompt can react to
 * delivery that arrives late even when FCM works.
 *
 * Counted: background deliveries and the backlog released within [LateDeliveryRule.CATCH_UP_WINDOW_MS]
 * of the app coming to the foreground, whose delay beyond [LateDeliveryRule.LATE_AFTER_MS] was spent
 * online. Excluded: delays over [LateDeliveryRule.MAX_LATENCY_MS], and the part of a delay spent
 * without a validated network when this process observed it ([ValidatedNetworkTracker]).
 * Latency is trusted server time minus the envelope's server timestamp, like
 * [KeepAliveHealthCounters.onEnvelopeDelivered]; an unanchored clock skips the sample.
 */
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

    /** Days are local calendar days: a backlog released around 08:00 Beijing time must not straddle a UTC midnight. */
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
            // Tracking can start after the app is already in front (cold launch): that moment is its foreground.
            if (AppForegroundObserver.isForegrounded()) lastForegroundElapsed = elapsedClock()
            AppForegroundObserver.addListener(object : AppForegroundObserver.Listener {
                override fun onForeground() {
                    lastForegroundElapsed = elapsedClock()
                }
            })
        }
    }

    /**
     * Call once for every newly stored message that arrived on a live delivery channel (socket, FCM
     * payload or server pull), never for redeliveries, duplicates or local retry queues. Safe on any
     * thread; the write runs asynchronously.
     */
    fun onEnvelopeDelivered(serverTimestamp: Long) {
        if (serverTimestamp <= 0L) return
        val now = serverNow() ?: return
        val latency = now - serverTimestamp
        if (latency <= LateDeliveryRule.LATE_AFTER_MS || latency > LateDeliveryRule.MAX_LATENCY_MS) return
        if (!isBackgroundOrCatchUp()) return
        // Unknown network history is skipped: an offline delay must never count as late.
        val lateOnline = onlineMsWithin(latency) ?: return
        if (lateOnline <= LateDeliveryRule.LATE_AFTER_MS) return
        pending.incrementAndGet()
        scheduleFlush()
        healthCounters.onLateDelivery()
    }

    /** Late deliveries in the rolling window, including counts not yet written. */
    suspend fun lateCount(): Int =
        count(dataStore.data.first()[AppStateKeys.BG_LATE_DELIVERY_DAYS], localWall()) + pending.get()

    /** Days in the rolling window with at least one late delivery, today's unwritten count included. */
    suspend fun lateDays(): Int {
        val nowWall = localWall()
        val days = window(dataStore.data.first()[AppStateKeys.BG_LATE_DELIVERY_DAYS], nowWall).filterValues { it > 0 }.keys
        return if (pending.get() > 0) (days + Math.floorDiv(nowWall, DAY_MS)).size else days.size
    }

    /** Writes the pending counts; concurrent callers coalesce into the one that holds the flag. */
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

        /** Day counts still inside the window; a count dated after today (clock moved back) empties it. */
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
