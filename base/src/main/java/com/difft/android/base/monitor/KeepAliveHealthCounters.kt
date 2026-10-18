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

/**
 * #1328: hot-path event counters for the daily [KeepAliveHealthReporter] report (FCM receipts,
 * FCM-triggered pull results, background delivery latency per channel, keep-alive service uptime).
 *
 * Recording only touches memory under a short lock; nothing here does IO on the caller's thread.
 * Pending counts reach app_state on the first event of a process (FCM-woken processes are often
 * killed within minutes), then at most once per [FLUSH_INTERVAL_MS] (piggybacking on events and on
 * the keep-alive alarm tick, never a wakeup of its own), on service stop and when the app goes to
 * the background, so a process kill loses at most one interval of counts.
 *
 * Latency is skipped until the trusted clock is anchored, so a cold FCM-woken process that has made
 * no API call yet contributes no latency sample.
 */
@Singleton
class KeepAliveHealthCounters @Inject constructor(
    @param:AppStateDataStore
    private val appStateDataStore: DataStore<Preferences>
) {

    companion object {
        internal const val FLUSH_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * Minimum spacing of the extra flush triggered by an FCM pull result. The pull result is the
         * last event of an FCM wake-up, so flushing then captures the payload sample and the pull
         * result together before the process is reclaimed; the floor bounds this to ~2 writes/min.
         */
        internal const val PULL_FLUSH_FLOOR_MS = 30 * 1000L
    }

    internal var elapsedClock: () -> Long = { SystemClock.elapsedRealtime() }
    internal var serverNow: () -> Long? = { if (ServerTimeProvider.isAnchored()) ServerTimeProvider.nowMillis() else null }
    internal var isForeground: () -> Boolean = { AppForegroundObserver.isForegrounded() }
    internal var wallClock: () -> Long = { System.currentTimeMillis() }

    /**
     * False when this install is not reporting (see [KeepAliveHealthModel.isEligible]). Set by the
     * reporter on every decision; `MessageServiceManager.setKeepAlive` sets it to true when the user
     * enables keep-alive, until the next decision.
     */
    @Volatile
    var collecting: Boolean = true

    private val lock = Any()
    private val pending = HashMap<String, Int>()

    // elapsedRealtime the uptime was last accrued from while the service runs; null when stopped.
    private var fgsAccruedFrom: Long? = null
    internal var lastFlushElapsed: Long? = null
    internal var lastPullFlushElapsed: Long? = null

    internal val backgroundListener = object : AppForegroundObserver.Listener {
        override fun onBackground() = flush(force = true)
    }

    init {
        runCatching { AppForegroundObserver.addListener(backgroundListener) }
    }

    fun onFcmReceived(deprioritized: Boolean) {
        if (!collecting) return
        synchronized(lock) {
            increment(HealthEventKeys.FCM_RECV)
            if (deprioritized) increment(HealthEventKeys.FCM_DEPRIO)
        }
        flush(force = false)
    }

    /** Result of an FCM-triggered HTTP pull; other pull triggers are not reported here. */
    fun onPullResult(success: Boolean) {
        if (!collecting) return
        synchronized(lock) { increment(if (success) HealthEventKeys.PULL_OK else HealthEventKeys.PULL_FAIL) }
        flush(force = false, pullResult = true)
    }

    /**
     * Records the latency of a message that was newly stored; the caller must not call this for
     * redeliveries or duplicates (the store's insert check decides).
     *
     * @param serverTimestamp server arrival time of the envelope (`systemShowTimestamp`).
     * @param channel path the envelope arrived on; selects the latency counter.
     */
    fun onEnvelopeDelivered(serverTimestamp: Long, channel: DeliveryChannel) {
        if (!collecting) return
        synchronized(lock) {
            if (serverTimestamp <= 0L || isForeground()) return
            val now = serverNow() ?: return
            increment(KeepAliveHealthModel.latencyKey(channel, now - serverTimestamp))
        }
        flush(force = false)
    }

    fun onServiceStarted() {
        synchronized(lock) {
            if (fgsAccruedFrom != null) return
            fgsAccruedFrom = elapsedClock()
            if (collecting) increment(HealthEventKeys.FGS_STARTS)
        }
        // A short-lived revival can die before any alarm tick; same throttled flush as other events.
        flush(force = false)
    }

    fun onServiceStopped() {
        synchronized(lock) {
            accrueUptime()
            fgsAccruedFrom = null
        }
        flush(force = true)
    }

    /** Keep-alive alarm tick: accrues uptime and persists if an interval has passed. */
    fun onAlarmTick() = flush(force = false)

    /** Takes all pending counts (uptime accrued up to now) and resets them. */
    fun drain(): Map<String, Int> = synchronized(lock) {
        accrueUptime()
        val out = HashMap(pending)
        pending.clear()
        out
    }

    /** Persisted + pending counts are dropped by the reporter; this drops the in-memory part. */
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

    // Caller holds lock.
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
