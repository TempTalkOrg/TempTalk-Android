package com.difft.android.network.speedtest

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.network.config.GlobalConfigsManager
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates domain speed testing and best-host selection.
 *
 * Maintains a ranked snapshot of host latencies and provides synchronous
 * best-host lookup with a 4-level fallback chain:
 * 1. Fastest available host from speed test snapshot
 * 2. Previously persisted best host (from SharedPreferences)
 * 3. First chat host from GlobalConfig
 * 4. null (caller falls back to hardcoded default)
 */
@Singleton
class DomainSpeedTestCoordinator @Inject constructor(
    private val globalConfigsManager: Lazy<GlobalConfigsManager>,
    private val speedTester: DomainSpeedTester
) {
    companion object {
        private const val TAG = "SpeedTest"
        private const val SP_KEY_BEST_HOST = "sp_speed_test_success_host"
        private const val SERVICE_TYPE_CHAT = "chat"

        private const val THROTTLE_MS = 30_000L
        private const val INITIAL_DELAY_MS = 10_000L
        private const val PERIODIC_FOREGROUND_MS = 30 * 60 * 1000L
        private const val WS_FAILURE_THRESHOLD = 3
    }

    private val snapshot = AtomicReference<List<HostSpeedResult>>(emptyList())
    private val invalidatedHostsThisSession: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lastTestTime = AtomicLong(0L)
    private val isTestRunning = AtomicBoolean(false)
    private val wsConsecutiveFailures = AtomicInteger(0)

    private var periodicJob: Job? = null

    fun initialize() {
        L.i { "[$TAG] initialize" }
        appScope.launch(Dispatchers.IO) {
            delay(INITIAL_DELAY_MS)
            runSpeedTest()
        }
    }

    /**
     * Returns the best available host synchronously (for URL construction).
     * Uses 4-level fallback: snapshot -> persisted -> GlobalConfig -> null.
     */
    fun getBestHostSync(): String? {
        // Level 1: fastest available host from snapshot
        val snapshotResult = snapshot.get()
            .firstOrNull { it.isAvailable && it.host !in invalidatedHostsThisSession }
        if (snapshotResult != null) {
            return snapshotResult.host
        }

        // Level 2: persisted best host from last speed test
        val persisted = SharedPrefsUtil.getString(SP_KEY_BEST_HOST)
        if (!persisted.isNullOrBlank() && persisted !in invalidatedHostsThisSession) {
            L.i { "[$TAG] getBestHostSync: using persisted host=$persisted" }
            return persisted
        }

        // Level 3: first chat host from GlobalConfig (excluding invalidated)
        val configHost = getChatHostsFromConfig()
            .firstOrNull { it !in invalidatedHostsThisSession }
        if (configHost != null) {
            L.i { "[$TAG] getBestHostSync: using GlobalConfig host=$configHost" }
            return configHost
        }

        // Level 4: null (caller falls back to hardcoded default)
        L.w { "[$TAG] getBestHostSync: no host available, returning null" }
        return null
    }

    /**
     * Returns all hosts ranked by latency (for HTTP retry fallback).
     */
    fun getAllHostsRanked(): List<String> {
        val ranked = snapshot.get()
            .filter { it.host !in invalidatedHostsThisSession }
            .map { it.host }

        if (ranked.isNotEmpty()) return ranked

        // Fallback to GlobalConfig host list (excluding invalidated)
        return getChatHostsFromConfig().filter { it !in invalidatedHostsThisSession }
    }

    /**
     * Marks a host as unavailable for this session (in-memory only, not persisted).
     * The host will be skipped in [getBestHostSync] and [getAllHostsRanked].
     */
    fun markHostUnavailable(host: String) {
        invalidatedHostsThisSession.add(host)

        // CAS loop to update snapshot
        while (true) {
            val current = snapshot.get()
            val updated = current.map {
                if (it.host == host) it.copy(isAvailable = false) else it
            }
            if (snapshot.compareAndSet(current, updated)) break
        }

        L.i { "[$TAG] markHostUnavailable: $host, invalidated=${invalidatedHostsThisSession.toList()}" }
    }

    /**
     * Triggers a speed test with 30s throttle to avoid excessive probing.
     */
    fun triggerSpeedTest() {
        val now = System.currentTimeMillis()
        val last = lastTestTime.get()
        if (now - last < THROTTLE_MS) {
            L.d { "[$TAG] triggerSpeedTest throttled" }
            return
        }
        appScope.launch(Dispatchers.IO) {
            runSpeedTest()
        }
    }

    /**
     * Called on each WebSocket connection failure.
     * Triggers a speed test after [WS_FAILURE_THRESHOLD] consecutive failures.
     */
    fun onWsFailure() {
        val count = wsConsecutiveFailures.incrementAndGet()
        L.d { "[$TAG] onWsFailure consecutiveCount=$count" }
        if (count >= WS_FAILURE_THRESHOLD) {
            wsConsecutiveFailures.set(0)
            L.i { "[$TAG] onWsFailure: threshold reached, triggering speed test" }
            triggerSpeedTest()
        }
    }

    fun onWsConnected() {
        wsConsecutiveFailures.set(0)
    }

    /**
     * Starts or stops periodic speed tests based on app foreground state.
     * Foreground: test every 30 minutes. Background: stop testing.
     */
    fun startPeriodicTest(isForeground: Boolean) {
        periodicJob?.cancel()
        if (!isForeground) {
            L.d { "[$TAG] periodic test stopped (background)" }
            return
        }

        periodicJob = appScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PERIODIC_FOREGROUND_MS)
                runSpeedTest()
            }
        }
        L.i { "[$TAG] periodic test started (foreground, interval=${PERIODIC_FOREGROUND_MS / 60_000}min)" }
    }

    /**
     * Resets all in-memory state. Called on logout before app restart.
     */
    fun resetSession() {
        periodicJob?.cancel()
        snapshot.set(emptyList())
        invalidatedHostsThisSession.clear()
        lastTestTime.set(0L)
        isTestRunning.set(false)
        wsConsecutiveFailures.set(0)
        L.i { "[$TAG] session reset" }
    }

    private suspend fun runSpeedTest() {
        if (!isTestRunning.compareAndSet(false, true)) return
        try {
            lastTestTime.set(System.currentTimeMillis())

            val hosts = getChatHostsFromConfig()
            if (hosts.isEmpty()) {
                L.w { "[$TAG] no hosts from GlobalConfig, skip speed test" }
                return
            }

            L.i { "[$TAG] speed test started for ${hosts.size} hosts: $hosts" }
            val results = speedTester.testHosts(hosts)

            // Update snapshot first, then clear invalidations. This ordering ensures that
            // a concurrent markHostUnavailable() call between these two lines won't lose
            // its add() — the clear() runs after, but the host's isAvailable in snapshot
            // is already set to false by markHostUnavailable's CAS loop.
            snapshot.set(results)
            invalidatedHostsThisSession.clear()

            val best = results.firstOrNull { it.isAvailable }
            if (best != null) {
                SharedPrefsUtil.putString(SP_KEY_BEST_HOST, best.host)
            }
            L.i { "[$TAG] speed test completed, best=${best?.host} (${best?.latencyMs}ms), all=$results" }
        } finally {
            isTestRunning.set(false)
        }
    }

    private fun getChatHostsFromConfig(): List<String> {
        return globalConfigsManager.get().getNewGlobalConfigs()
            ?.data?.hosts
            ?.filter { it.servTo == SERVICE_TYPE_CHAT }
            ?.mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }
            .orEmpty()
    }
}
