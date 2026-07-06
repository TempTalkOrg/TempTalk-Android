package com.difft.android.network.speedtest

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.appScope
import com.difft.android.network.ServiceUrlResolver
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.proxy.ProxyConfigProvider
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
    private val speedTester: DomainSpeedTester,
    private val userManager: UserManager,
    private val proxyConfigProvider: Lazy<ProxyConfigProvider>,
) {
    /**
     * Whether the proxy is currently active. Reads the injected INSTANCE
     * [ProxyConfigProvider.isEnabled] (which triggers `refreshFromUserDataIfChanged`)
     * rather than the static [ProxyConfigProvider.isProxyActive] mirror: the static
     * is only refreshed as a side effect of other components calling an instance
     * method, so on a cold start where the proxy was enabled last session it can
     * still read `false` before anyone refreshes it. If a speed test ran in that
     * window it would probe LIVE hosts through the proxy client; those hosts are not
     * on the embedded whitelist, so [shouldTunnel] would route them DIRECT and leak
     * the real IP. The instance read self-heals that window.
     */
    private fun isProxyActive(): Boolean = proxyConfigProvider.get().isEnabled
    companion object {
        private const val TAG = "SpeedTest"

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

    @Volatile
    private var periodicJob: Job? = null

    fun initialize() {
        L.i { "[$TAG] initialize" }
        appScope.launch(Dispatchers.IO) {
            // StoragePreloader pre-warms the global config before this 10s-delayed
            // first run, so getNewGlobalConfigs() resolves synchronously from
            // memory/assets and getChatHostsFromConfig() is non-empty (design MED-1).
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

        // Level 2: persisted best host from last speed test.
        // Synchronous lookup via UserManager's in-memory snapshot — no I/O.
        val persisted = userManager.getUserData()?.bestHost
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
     * Returns the first host from [candidates] not marked unavailable this
     * session, or null when all are invalidated. Used by the proxy path to keep
     * failover (e.g. WebSocket host switching) working over the embedded host
     * set without depending on the speed-test snapshot/ranking.
     */
    fun firstAvailableHost(candidates: List<String>): String? =
        candidates.firstOrNull { it !in invalidatedHostsThisSession }

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
    @Synchronized
    fun startPeriodicTest(isForeground: Boolean) {
        periodicJob?.cancel()
        if (!isForeground) {
            L.d { "[$TAG] periodic test stopped (background)" }
            return
        }

        periodicJob = appScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PERIODIC_FOREGROUND_MS)
                if (isProxyActive()) {
                    // Proxy mode skips speed testing (all embedded hosts resolve to the
                    // same proxy IP, so latency ranking is meaningless). But failover
                    // still marks embedded hosts unavailable on WS failure; runSpeedTest
                    // — the only other site that clears that set — is skipped under the
                    // proxy, so without this periodic clear a host marked unavailable
                    // would never be retried even after it recovers, permanently
                    // degrading failover. Clear on the same cadence the speed test would
                    // have cleared, giving recovered hosts a fresh chance.
                    invalidatedHostsThisSession.clear()
                    L.i { "[$TAG] proxy active: cleared invalidated hosts (no speed test)" }
                } else {
                    runSpeedTest()
                }
            }
        }
        L.i { "[$TAG] periodic test started (foreground, interval=${PERIODIC_FOREGROUND_MS / 60_000}min)" }
    }

    /**
     * Resets all in-memory state. Called on logout before app restart.
     */
    @Synchronized
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
        // Proxy active: skip speed testing entirely. Every embedded host resolves to
        // the same proxy IP, so probing them measures the proxy hop, not the host —
        // the ranking is meaningless and the probes would only add load. Host
        // failover under the proxy is handled by [firstAvailableHost] over the
        // embedded set, with its invalidation reset on the periodic clear in
        // [startPeriodicTest] (see B2 / light_clear). Read the fresh instance state
        // (see [isProxyActive]) so a cold-start where the proxy is enabled is honored
        // before any speed test would have leaked to a live host.
        if (isProxyActive()) {
            L.i { "[$TAG] proxy active: skip speed test" }
            return
        }
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
                userManager.update { bestHost = best.host }
            }
            L.i { "[$TAG] speed test completed, best=${best?.host} (${best?.latencyMs}ms), all=$results" }
        } finally {
            isTestRunning.set(false)
        }
    }

    /**
     * Chat speed-test host pool, resolved from the `services` + `domains` model
     * via [ServiceUrlResolver] (same source as the URL path resolver, so the
     * speed-test pool and routed URLs never diverge). No legacy
     * `hosts(servTo==chat)` fallback: the assets default config always carries
     * services + domains, so there is no reachable "hosts-but-no-services" state,
     * and mixing models would let failover hit a host the server has retired.
     * Returns empty when unresolved; the empty case is
     * logged once at each decision point ([runSpeedTest] / [getBestHostSync]),
     * not here — this shared accessor stays silent to avoid hot-path log spam.
     */
    private fun getChatHostsFromConfig(): List<String> =
        ServiceUrlResolver.resolve(
            globalConfigsManager.get().getNewGlobalConfigs()?.data, ServiceUrlResolver.SERVICE_NAME_CHAT
        )?.hosts.orEmpty()
}
