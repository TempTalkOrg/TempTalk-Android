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





















@Singleton
class DomainSpeedTestCoordinator internal constructor(
    private val globalConfigsManager: Lazy<GlobalConfigsManager>,
    private val speedTester: DomainSpeedTester,
    private val userManager: UserManager,
    private val proxyConfigProvider: Lazy<ProxyConfigProvider>,
    
    private val networkAvailable: () -> Boolean,
    private val clock: () -> Long,
    
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

    




    private fun isProxyActive(): Boolean = proxyConfigProvider.get().isEnabled

    companion object {
        private const val TAG = "SpeedTest"

        private const val THROTTLE_MS = 30_000L
        private const val INITIAL_DELAY_MS = 10_000L
        private const val PERIODIC_FOREGROUND_MS = 30 * 60 * 1000L
        private const val NETWORK_AVAILABLE_PROBE_MS = 60_000L
        private const val WS_FAILURE_THRESHOLD = 3
    }

    
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
            
            
            delay(INITIAL_DELAY_MS)
            runSpeedTest()
        }
    }

    

    




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

        
        state.ranked.firstOrNull { it.host in pool && state.isEligible(it.host, probeApplies) }
            ?.let { return it.host }

        
        
        val persisted = userManager.getUserData()?.bestHost
        if (!persisted.isNullOrBlank() && persisted in pool && state.isEligible(persisted, probeApplies)) {
            
            if (persisted != lastLoggedPersistedHost) {
                lastLoggedPersistedHost = persisted
                L.i { "[$TAG] getBestHostSync: using persisted host=$persisted" }
            }
            return persisted
        }

        
        val configHost = pool.firstOrNull { state.isEligible(it, probeApplies) } ?: return null
        if (configHost != lastLoggedConfigHost) {
            lastLoggedConfigHost = configHost
            L.i { "[$TAG] getBestHostSync: using GlobalConfig host=$configHost" }
        }
        return configHost
    }

    





    fun firstAvailableHost(candidates: List<String>): String? = eligibleHosts(candidates).firstOrNull()

    
    fun eligibleHosts(candidates: List<String>): List<String> =
        eligibleOrReset(candidates, probeApplies = !isProxyActive())

    




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

    



    private fun recoverFromExhaustion(pool: List<String>) {
        reset("exhausted pool=${pool.size}", pool)
        triggerSpeedTest()
    }

    
    private fun currentPool(proxy: Boolean): List<String> =
        if (proxy) globalConfigsManager.get().getProxyTunnelChatDomains() else configChatHosts()

    
    fun isInPool(host: String): Boolean = host in currentPool(isProxyActive())

    

    







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
        
        
        if (pool.size == 1) return
        record(host, available = false)
        L.i { "[$TAG] markHostUnavailable host=$host" }
    }

    
    fun isNetworkAvailable(): Boolean = networkAvailable()

    




    fun markHostHealthy(host: String) {
        
        
        if (!isTestRunning.get() && !store.get().isDown(host)) return
        if (host !in currentPool(isProxyActive())) return
        val wasDown = store.get().isDown(host)
        record(host, available = true)
        
        if (wasDown) L.i { "[$TAG] markHostHealthy host=$host" }
    }

    



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

    



    internal fun publishProbeResults(results: List<HostSpeedResult>, startGeneration: Long) {
        store.updateAndGet { current ->
            HostStore(
                ranked = results,
                local = current.local.filterValues { it.generation > startGeneration },
            )
        }
    }

    
    internal fun currentGeneration(): Long = generation.get()

    

    


    fun triggerSpeedTest() {
        val now = clock()
        val last = lastTestTime.get()
        if (now - last < THROTTLE_MS) {
            L.d { "[$TAG] triggerSpeedTest throttled" }
            return
        }
        launchProbe { runSpeedTest() }
    }

    



    fun onNetworkAvailable() {
        if (clock() - lastProbeBaseline() < NETWORK_AVAILABLE_PROBE_MS) return
        L.i { "[$TAG] network available, requesting speed test" }
        triggerSpeedTest()
    }

    
    private fun lastProbeBaseline(): Long = maxOf(lastTestTime.get(), createdAt)

    



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
            
            
            resetAvailability("proxy periodic")
        } else {
            runSpeedTest()
        }
    }

    


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
        
        
        
        if (isProxyActive()) {
            L.i { "[$TAG] proxy active: skip speed test" }
            return
        }
        if (!isTestRunning.compareAndSet(false, true)) return
        try {
            
            
            val startGeneration = generation.get()
            val previousTestTime = lastTestTime.getAndSet(clock())

            val hosts = configChatHosts()
            if (hosts.isEmpty()) {
                L.w { "[$TAG] no hosts from GlobalConfig, skip speed test" }
                return
            }

            L.i { "[$TAG] speed test started for ${hosts.size} hosts: $hosts" }
            val results = speedTester.testHosts(hosts)

            
            
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

    












    private fun configChatHosts(): List<String> {
        val data = globalConfigsManager.get().getNewGlobalConfigs()?.data
        configPool.get()?.takeIf { it.source === data }?.let { return it.hosts }
        val hosts = ServiceUrlResolver.resolve(data, ServiceUrlResolver.SERVICE_NAME_CHAT)?.hosts.orEmpty()
        configPool.set(ConfigPool(data, hosts))
        return hosts
    }
}
