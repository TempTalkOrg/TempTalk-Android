package com.difft.android.network.speedtest

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.Data
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.NetworkUtils
import com.difft.android.base.utils.appScope
import com.difft.android.network.ServiceUrlResolver
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.proxy.ProxyConfigProvider
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates domain speed testing and best-host selection, and owns the shared bad-host state
 * that HTTP failover and the WebSocket both read and write (#557, #1396).
 *
 * A host is **eligible** when it is in the current pool (the config chat hosts, or the proxy
 * tunnel domains while the proxy is on), has not been marked unavailable, and the last probe did
 * not report it down (probe results are ignored while the proxy is on, because they describe the
 * direct path). Every selection tier goes through the one predicate, in this order:
 * 1. Fastest eligible host from the last speed test
 * 2. Previously persisted best host, if it is eligible
 * 3. First eligible chat host from GlobalConfig
 * 4. null (caller falls back to the hardcoded default)
 *
 * When no pool host is eligible the coordinator resets availability and requests a probe, so a
 * pool that every failure has marked recovers instead of staying empty.
 *
 * Marks, healthy updates and resets carry a generation from one monotonic counter. A probe
 * remembers the generation it started at, and its result never overwrites a host that changed
 * after that.
 */
@Singleton
class DomainSpeedTestCoordinator internal constructor(
    private val globalConfigsManager: Lazy<GlobalConfigsManager>,
    private val speedTester: DomainSpeedTester,
    private val userManager: UserManager,
    private val proxyConfigProvider: Lazy<ProxyConfigProvider>,
    /** Whether the device has a usable network; marks are dropped while it does not. */
    private val networkAvailable: () -> Boolean,
    private val clock: () -> Long,
    /** Starts a probe off the caller's thread. */
    private val launchProbe: (suspend () -> Unit) -> Unit,
) {

    @Inject
    constructor(
        globalConfigsManager: Lazy<GlobalConfigsManager>,
        speedTester: DomainSpeedTester,
        userManager: UserManager,
        proxyConfigProvider: Lazy<ProxyConfigProvider>,
        @ApplicationContext context: Context,
    ) : this(
        globalConfigsManager = globalConfigsManager,
        speedTester = speedTester,
        userManager = userManager,
        proxyConfigProvider = proxyConfigProvider,
        networkAvailable = { NetworkUtils.isNetworkAvailable(context) },
        clock = System::currentTimeMillis,
        launchProbe = { probe -> appScope.launch(Dispatchers.IO) { probe() } },
    )

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
        private const val NETWORK_AVAILABLE_PROBE_MS = 60_000L
        private const val WS_FAILURE_THRESHOLD = 3
    }

    /** The config chat pool, cached against the config instance it was resolved from. */
    private class ConfigPool(val source: Data?, val hosts: List<String>)

    private val store = AtomicReference(HostStore())
    private val generation = AtomicLong(0L)
    private val configPool = AtomicReference<ConfigPool?>(null)

    @Volatile
    private var lastLoggedPersistedHost: String? = null

    @Volatile
    private var lastLoggedConfigHost: String? = null

    private val createdAt = clock()
    private val lastTestTime = AtomicLong(0L)
    private val isTestRunning = AtomicBoolean(false)
    private val wsConsecutiveFailures = AtomicInteger(0)
    private val offlineMarkLogged = AtomicBoolean(false)

    @Volatile
    private var periodicJob: Job? = null

    fun initialize() {
        L.i { "[$TAG] initialize" }
        appScope.launch(Dispatchers.IO) {
            // StoragePreloader pre-warms the global config before this 10s-delayed
            // first run, so getNewGlobalConfigs() resolves synchronously from
            // memory/assets and the chat host pool is non-empty (design MED-1).
            delay(INITIAL_DELAY_MS)
            runSpeedTest()
        }
    }

    // ==================== Host selection ====================

    /**
     * Returns the best eligible host synchronously (for URL construction).
     * Uses the tiers listed on the class. When the pool is non-empty but nothing is eligible,
     * availability is reset and the first tier that now matches wins.
     */
    fun getBestHostSync(): String? {
        val proxy = isProxyActive()
        val pool = currentPool(proxy)
        pickBest(pool, probeApplies = !proxy)?.let { return it }

        if (pool.isEmpty()) {
            L.w { "[$TAG] getBestHostSync: no host available, returning null" }
            return null
        }
        recoverFromExhaustion(pool)
        return pickBest(pool, probeApplies = !proxy)
    }

    private fun pickBest(pool: List<String>, probeApplies: Boolean): String? {
        val state = store.get()

        // Level 1: fastest eligible host from the last probe
        state.ranked.firstOrNull { it.host in pool && state.isEligible(it.host, probeApplies) }
            ?.let { return it.host }

        // Level 2: persisted best host from the last speed test, only while it is still eligible.
        // Synchronous lookup via UserManager's in-memory snapshot — no I/O.
        val persisted = userManager.getUserData()?.bestHost
        if (!persisted.isNullOrBlank() && persisted in pool && state.isEligible(persisted, probeApplies)) {
            // Called on every URL build; log the choice only when it changes.
            if (persisted != lastLoggedPersistedHost) {
                lastLoggedPersistedHost = persisted
                L.i { "[$TAG] getBestHostSync: using persisted host=$persisted" }
            }
            return persisted
        }

        // Level 3: first eligible chat host from GlobalConfig
        val configHost = pool.firstOrNull { state.isEligible(it, probeApplies) } ?: return null
        if (configHost != lastLoggedConfigHost) {
            lastLoggedConfigHost = configHost
            L.i { "[$TAG] getBestHostSync: using GlobalConfig host=$configHost" }
        }
        return configHost
    }

    /**
     * Returns the first eligible host of [candidates], or null when [candidates] is empty. Used by
     * the proxy path to keep failover (HTTP and WebSocket host switching) working over the embedded
     * host set without depending on the speed-test ranking. When every candidate is marked,
     * availability is reset and the first candidate is returned.
     */
    fun firstAvailableHost(candidates: List<String>): String? = eligibleHosts(candidates).firstOrNull()

    /** The eligible hosts of [candidates], in order; a reset first when none of them is eligible. */
    fun eligibleHosts(candidates: List<String>): List<String> =
        eligibleOrReset(candidates, probeApplies = !isProxyActive())

    /**
     * Returns the eligible hosts ranked by latency (for HTTP retry fallback); falls back to the
     * eligible config hosts when no ranked host is eligible, resetting availability when even those
     * are exhausted.
     */
    fun getAllHostsRanked(): List<String> {
        val proxy = isProxyActive()
        val pool = currentPool(proxy)
        val state = store.get()
        val ranked = state.ranked
            .filter { it.host in pool && state.isEligible(it.host, !proxy) }
            .map { it.host }
        if (ranked.isNotEmpty()) return ranked
        return eligibleOrReset(pool, probeApplies = !proxy)
    }

    private fun eligibleOrReset(pool: List<String>, probeApplies: Boolean): List<String> {
        val eligible = pool.filter { store.get().isEligible(it, probeApplies) }
        if (eligible.isNotEmpty() || pool.isEmpty()) return eligible
        recoverFromExhaustion(pool)
        return pool.filter { store.get().isEligible(it, probeApplies) }
    }

    /**
     * Every pool host is marked or probed down. Make them all eligible at once, even when the probe
     * is throttled or skipped (the proxy never probes), and ask for a fresh probe.
     */
    private fun recoverFromExhaustion(pool: List<String>) {
        reset("exhausted pool=${pool.size}", pool)
        triggerSpeedTest()
    }

    /** The pool host marks and probe results are checked against. */
    private fun currentPool(proxy: Boolean): List<String> =
        if (proxy) globalConfigsManager.get().getProxyTunnelChatDomains() else configChatHosts()

    /** Whether [host] belongs to the chat pool in use now (the tunnel domains under the proxy). */
    fun isInPool(host: String): Boolean = host in currentPool(isProxyActive())

    // ==================== Shared state ====================

    /**
     * Marks a host as unavailable for this session (in-memory only, not persisted). It is skipped
     * by every selection tier until it is marked healthy, a probe finds it up, or availability is
     * reset.
     *
     * Dropped while the device has no usable network, so one offline window does not consume the
     * pool, for hosts outside the current pool, and when the pool has a single host.
     */
    fun markHostUnavailable(host: String) {
        if (!networkAvailable()) {
            if (offlineMarkLogged.compareAndSet(false, true)) {
                L.i { "[$TAG] markHostUnavailable skipped: no network host=$host" }
            }
            return
        }
        offlineMarkLogged.set(false)
        val pool = currentPool(isProxyActive())
        if (host !in pool) {
            L.i { "[$TAG] markHostUnavailable skipped: not in pool host=$host" }
            return
        }
        // A sole host has nowhere to fail over to, and marking it would only exhaust the pool and
        // reset it again on the next selection, once per failed request.
        if (pool.size == 1) return
        record(host, available = false)
        L.i { "[$TAG] markHostUnavailable host=$host" }
    }

    /** Whether the device has a usable network; failover neither marks nor resends while it does not. */
    fun isNetworkAvailable(): Boolean = networkAvailable()

    /**
     * Records that [host] answered (an HTTP response below 500, or a WebSocket connect), clearing a
     * mark an earlier failure left on it. Called on every answered request, so it returns without
     * writing when the host is not marked down and no probe is in flight.
     */
    fun markHostHealthy(host: String) {
        // A probe in flight may still publish "down" for this host, so the healthy answer must be
        // recorded then even when the host looks fine now.
        if (!isTestRunning.get() && !store.get().isDown(host)) return
        if (host !in currentPool(isProxyActive())) return
        val wasDown = store.get().isDown(host)
        record(host, available = true)
        // While a probe is in flight every answer is recorded; only a real down to up change is news.
        if (wasDown) L.i { "[$TAG] markHostHealthy host=$host" }
    }

    /**
     * Makes every known host available again, under a new generation so a probe that started before
     * the reset cannot undo it. Used when the pool is exhausted and by the periodic proxy clear.
     */
    fun resetAvailability(reason: String) = reset(reason, currentPool(isProxyActive()))

    private fun reset(reason: String, pool: List<String>) {
        store.updateAndGet { current ->
            val reset = HostState(available = true, generation = generation.incrementAndGet())
            val hosts = pool + current.ranked.map { it.host } + current.local.keys
            current.copy(local = hosts.associateWith { reset })
        }
        L.i { "[$TAG] resetAvailability reason=$reason" }
    }

    private fun record(host: String, available: Boolean) {
        store.updateAndGet { current ->
            val state = HostState(available, generation.incrementAndGet())
            current.copy(local = current.local + (host to state))
        }
    }

    /**
     * Publishes a probe result. A host whose local state is newer than the probe's start keeps it;
     * every other host takes the probe's verdict.
     */
    internal fun publishProbeResults(results: List<HostSpeedResult>, startGeneration: Long) {
        store.updateAndGet { current ->
            HostStore(
                ranked = results,
                local = current.local.filterValues { it.generation > startGeneration },
            )
        }
    }

    /** The generation a probe started now would record; for tests. */
    internal fun currentGeneration(): Long = generation.get()

    // ==================== Probe triggers ====================

    /**
     * Triggers a speed test with 30s throttle to avoid excessive probing.
     */
    fun triggerSpeedTest() {
        val now = clock()
        val last = lastTestTime.get()
        if (now - last < THROTTLE_MS) {
            L.d { "[$TAG] triggerSpeedTest throttled" }
            return
        }
        launchProbe { runSpeedTest() }
    }

    /**
     * The device gained a network: probe, at most once a minute, because the ranking may predate
     * the outage. The 30 s floor of [triggerSpeedTest] still applies.
     */
    fun onNetworkAvailable() {
        if (clock() - lastProbeBaseline() < NETWORK_AVAILABLE_PROBE_MS) return
        L.i { "[$TAG] network available, requesting speed test" }
        triggerSpeedTest()
    }

    /** The newest of the last probe and process start; the latter keeps a cold start from probing twice. */
    private fun lastProbeBaseline(): Long = maxOf(lastTestTime.get(), createdAt)

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
     * Foreground: probe at once when the last probe is 30 minutes old or more (the periodic timer
     * restarts on every foreground, so it alone never fires for a user who keeps returning), then
     * every 30 minutes. Background: stop testing.
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
                runPeriodicTick()
            }
        }
        L.i { "[$TAG] periodic test started (foreground, interval=${PERIODIC_FOREGROUND_MS / 60_000}min)" }
        if (clock() - lastProbeBaseline() >= PERIODIC_FOREGROUND_MS) {
            L.i { "[$TAG] foreground after a long gap, requesting speed test" }
            triggerSpeedTest()
        }
    }

    internal suspend fun runPeriodicTick() {
        if (isProxyActive()) {
            // Proxy mode skips speed testing (all embedded hosts resolve to the
            // same proxy IP, so latency ranking is meaningless). But failover
            // still marks embedded hosts unavailable and runSpeedTest, which would
            // otherwise refresh them, is skipped under the proxy. A mark otherwise
            // clears only when that host answers again or the pool is exhausted, so
            // reset on the cadence the speed test would have had, giving a recovered
            // host that sees no traffic a fresh chance.
            resetAvailability("proxy periodic")
        } else {
            runSpeedTest()
        }
    }

    /**
     * Resets all in-memory state. Called on logout before app restart.
     */
    @Synchronized
    fun resetSession() {
        periodicJob?.cancel()
        store.set(HostStore())
        lastTestTime.set(0L)
        isTestRunning.set(false)
        wsConsecutiveFailures.set(0)
        offlineMarkLogged.set(false)
        L.i { "[$TAG] session reset" }
    }

    internal suspend fun runSpeedTest() {
        // Proxy active: skip speed testing entirely. Every embedded host resolves to
        // the same proxy IP, so probing them measures the proxy hop, not the host —
        // the ranking is meaningless and the probes would only add load. Host
        // failover under the proxy is handled by [firstAvailableHost] over the
        // embedded set, with its marks reset in [runPeriodicTick]. Read the fresh instance state
        // (see [isProxyActive]) so a cold-start where the proxy is enabled is honored
        // before any speed test would have leaked to a live host.
        if (isProxyActive()) {
            L.i { "[$TAG] proxy active: skip speed test" }
            return
        }
        if (!isTestRunning.compareAndSet(false, true)) return
        try {
            // Taken after the running flag is set: a healthy answer that lands from here on sees
            // the flag and is recorded, and the publication below keeps it.
            val startGeneration = generation.get()
            val previousTestTime = lastTestTime.getAndSet(clock())

            val hosts = configChatHosts()
            if (hosts.isEmpty()) {
                L.w { "[$TAG] no hosts from GlobalConfig, skip speed test" }
                return
            }

            L.i { "[$TAG] speed test started for ${hosts.size} hosts: $hosts" }
            val results = speedTester.testHosts(hosts)

            // A probe that ran across an outage (Wi-Fi/data handover) fails every host for a reason
            // that says nothing about them: keep the old ranking and let the next trigger probe.
            if (!networkAvailable()) {
                lastTestTime.set(previousTestTime)
                L.i { "[$TAG] probe discarded: no network" }
                return
            }

            publishProbeResults(results, startGeneration)

            val best = results.firstOrNull { it.isAvailable }
            if (best != null) {
                userManager.update { bestHost = best.host }
            }
            L.i { "[$TAG] speed test completed best=${best?.host} (${best?.latencyMs}ms) all=${results.joinToString { "${it.host}:${it.latencyMs}ms/${it.isAvailable}" }}" }
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
     *
     * Runs on every request, so the resolution is cached against the config instance it came from.
     */
    private fun configChatHosts(): List<String> {
        val data = globalConfigsManager.get().getNewGlobalConfigs()?.data
        configPool.get()?.takeIf { it.source === data }?.let { return it.hosts }
        val hosts = ServiceUrlResolver.resolve(data, ServiceUrlResolver.SERVICE_NAME_CHAT)?.hosts.orEmpty()
        configPool.set(ConfigPool(data, hosts))
        return hosts
    }
}
