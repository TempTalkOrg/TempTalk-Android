package com.difft.android.call.manager

import com.difft.android.base.utils.globalServices

import com.difft.android.base.call.ServiceUrlDataV2
import com.difft.android.base.call.ServiceUrls
import com.difft.android.base.call.UrlInfo
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.SecureConfigStore
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.base.utils.appScope
import com.difft.android.call.data.CallStatisticsEvent
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.proxy.ProxyConfigProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Call service URL configuration manager: network fetch via [LCallHttpService.getServiceUrlV2],
 * memory + encrypted disk cache, versioning and TTL. Persists the complete [ServiceUrls] payload
 * and exposes the cached view through [getCachedServiceUrls] / [ensureServiceUrlsForCall].
 */
@Singleton
class CallServiceUrlManager @Inject constructor(
    private val statisticsLogManager: dagger.Lazy<CallStatisticsLogManager>,
    @ChativeHttpClientModule.Call private val callHttpClient: dagger.Lazy<ChativeHttpClient>,
    private val secureConfigStore: SecureConfigStore,
    private val proxyConfigProviderLazy: dagger.Lazy<ProxyConfigProvider>,
    private val globalConfigsManagerLazy: dagger.Lazy<IGlobalConfigsManager>,
) {

    private val callHttpService: LCallHttpService by lazy {
        callHttpClient.get().getService(LCallHttpService::class.java)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()
    private var memState: CallServiceUrlDiskState? = null
    private var loadedFromDisk = false

    private val fetchMutex = Mutex()

    /**
     * Decoupled throttle for [onAppForegrounded]. Stamped on every foreground fetch **attempt**
     * (regardless of outcome), so that an abnormal server response (e.g. `status=0` with
     * `serviceUrls == null`) or a network error does not leave [memState] with
     * `lastFetchedAtMillis == 0L` and defeat the 20-minute throttle on subsequent foreground events.
     */
    @Volatile
    private var lastForegroundAttemptAtMillis: Long = 0L

    private val foregroundRefreshMinIntervalMs = 20 * 60 * 1000L

    /**
     * Synthesizes a [ServiceUrls] from the proxy call tunnel domains
     * (`proxy.tunnelDomains.call`, live-preferred + embedded fallback). While the
     * proxy is active, call connections are forced onto these domains so the call
     * domain stays inside the relay's `ssl_preread` whitelist.
     *
     * Only the DOMAIN is needed: under the proxy, the meeting connection planner
     * connects by domain (WSS, or QUIC-over-proxy when the relay advertises `q`),
     * never by IP — so no `addrs` are synthesized. The first domain becomes
     * [ServiceUrls.primary]; the rest become [ServiceUrls.fallback]. Returns
     * `null` when no proxy call domain is configured (callers then fail closed
     * rather than connect direct).
     */
    private fun proxyServiceUrls(): ServiceUrls? {
        val domains = globalConfigsManagerLazy.get().getProxyTunnelCallDomains()
        val primaryDomain = domains.firstOrNull() ?: return null
        return ServiceUrls(
            config_version = 0,
            fallback = domains.drop(1).map { UrlInfo(addrs = emptyList(), domain = it, region = "") },
            primary = UrlInfo(addrs = emptyList(), domain = primaryDomain, region = ""),
            ttl = 0,
        )
    }

    /**
     * Loads the persisted [CallServiceUrlDiskState] from [SecureConfigStore] into the
     * in-memory cache. Must be invoked under [lock].
     *
     * **`runBlocking` bridge rationale**: the public surface of this class includes
     * non-suspend accessors ([getCachedServiceUrls]) and sync `synchronized(lock) { ... }`
     * blocks inside suspend methods. The DataStore is pre-warmed by `StoragePreloader`
     * at application startup (issue #725 Task 2), so `.first()` returns from the
     * in-memory cache without blocking on disk I/O. The bridge sits inside an
     * already-IO-bound caller (every public method dispatches to [Dispatchers.IO]
     * before touching this method) — see issue #725 design §3.7.
     */
    @Suppress("BanRunBlockingOutsideTests")
    private fun loadFromDiskLocked() {
        if (loadedFromDisk) return
        val raw = runBlocking(Dispatchers.IO) {
            secureConfigStore.callServiceUrlStateV3Flow.first()
        }
        memState = if (raw.isBlank()) {
            null
        } else {
            try {
                json.decodeFromString<CallServiceUrlDiskState>(raw)
            } catch (e: Exception) {
                L.e(e) { "[Call] CallServiceUrlManager load disk failed" }
                null
            }
        }
        loadedFromDisk = true
    }

    /**
     * Persists [state] via [SecureConfigStore] and updates [memState]. Must be invoked
     * under [lock]. Same `runBlocking` bridge rationale as [loadFromDiskLocked].
     */
    @Suppress("BanRunBlockingOutsideTests")
    private fun persistLocked(state: CallServiceUrlDiskState) {
        val encoded = json.encodeToString(state)
        runBlocking(Dispatchers.IO) {
            secureConfigStore.saveCallServiceUrlStateV3(encoded)
        }
        memState = state
    }

    private fun buildStateFromRemote(
        previous: CallServiceUrlDiskState?,
        remote: ServiceUrls,
        serverTimestamp: Long?,
    ): CallServiceUrlDiskState {
        val now = System.currentTimeMillis()
        val expiresAt = computeExpiresAtMillis(serverTimestamp, remote.ttl)
        val remoteStored = remote.toStored()

        if (previous?.serviceUrls != null && previous.serviceUrls != remoteStored) {
            L.i {
                "[Call] CallServiceUrlManager serviceUrls changed: " +
                    "version ${previous.configVersion()} → ${remote.config_version}, " +
                    "region ${previous.serviceUrls.primary?.region} → ${remoteStored.primary?.region}"
            }
        }

        return CallServiceUrlDiskState(
            expiresAtMillis = expiresAt,
            lastFetchedAtMillis = now,
            serviceUrls = remoteStored,
        )
    }

    /** Collects connection endpoint strings from structured config (order: primary → fallback). */
    private fun connectionEndpointsFromState(state: CallServiceUrlDiskState?): List<String> {
        val s = state?.serviceUrls?.toServiceUrls() ?: return FALLBACK_HARDCODED_URLS
        return connectionEndpointsFromServiceUrls(s)
    }

    private fun connectionEndpointsFromServiceUrls(s: ServiceUrls): List<String> {
        val ordered = LinkedHashSet<String>()
        s.primary?.addrs
            ?.asSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { ordered.add(it) }
        s.fallback.asSequence()
            .filterNotNull()
            .flatMap { it.addrs.asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { ordered.add(it) }
        return if (ordered.isEmpty()) FALLBACK_HARDCODED_URLS else ordered.toList()
    }

    /**
     * Triggered on app foreground from [ProcessLifecycleOwner] / [util.AppForegroundObserver],
     * i.e. on the **main thread**. The whole body is dispatched to [Dispatchers.IO] because the
     * throttle check itself touches the DataStore on first access via [loadFromDiskLocked].
     */
    fun onAppForegrounded() {
        appScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val lastFetch = synchronized(lock) {
                loadFromDiskLocked()
                memState?.lastFetchedAtMillis ?: 0L
            }
            // Take the max so that other successful refresh paths (e.g. ensureServiceUrlsForCall,
            // refreshAfterConnectionFailure) which update memState.lastFetchedAtMillis also count
            // toward the foreground throttle, and so that failed attempts — which do NOT update
            // memState — are still throttled via lastForegroundAttemptAtMillis.
            val throttleRef = maxOf(lastFetch, lastForegroundAttemptAtMillis)
            if (now - throttleRef < foregroundRefreshMinIntervalMs) return@launch
            lastForegroundAttemptAtMillis = now
            fetchCallServiceUrlAndCache()
        }
    }

    /**
     * For initiating calls: waits up to [timeoutMs] to fetch when cache is missing or expired, returns complete [ServiceUrls].
     *
     * Returns **null** if timed out or cache is still expired after fetch (e.g. network failure with stale data),
     * preventing use of expired configuration as valid.
     */
    suspend fun ensureServiceUrlsForCall(timeoutMs: Long = 15_000L): ServiceUrls? {
        // Proxy active for calls: hard-switch to the proxy call tunnel domains (no
        // live fetch, no fallback) so the call domain stays whitelist-aligned with
        // the relay. Gated on isEnabledForCall ("Protect IP address in calls") so
        // calls fall back to live URLs (direct) when the toggle is off. Read the
        // injected instance (not the static mirror) so the cold-start refresh
        // self-heals before warmUp.
        if (proxyConfigProviderLazy.get().isEnabledForCall) {
            return proxyServiceUrls()
        }
        val snapshot = synchronized(lock) {
            loadFromDiskLocked()
            memState
        }
        if (snapshot != null && !snapshot.isExpired()) {
            return snapshot.serviceUrls?.toServiceUrls()
        }
        withTimeoutOrNull(timeoutMs) {
            fetchCallServiceUrlAndCache()
        }
        return synchronized(lock) {
            loadFromDiskLocked()
            val m = memState
            when {
                m == null -> null
                m.isExpired() -> null
                else -> m.serviceUrls?.toServiceUrls()
            }
        }
    }

    suspend fun refreshAfterConnectionFailure() {
        val lastFetch = synchronized(lock) {
            loadFromDiskLocked()
            memState?.lastFetchedAtMillis ?: 0L
        }
        if (System.currentTimeMillis() - lastFetch < FAILURE_REFRESH_MIN_INTERVAL_MS) {
            L.d { "[Call] CallServiceUrlManager refreshAfterConnectionFailure throttled" }
            return
        }
        fetchCallServiceUrlAndCache()
    }

    suspend fun fetchCallServiceUrlAndCache(): List<String> = withContext(Dispatchers.IO) {
        fetchMutex.withLock {
            val recentlyFetched = synchronized(lock) {
                loadFromDiskLocked()
                val ms = memState
                ms != null && ms.lastFetchedAtMillis > 0L &&
                    (System.currentTimeMillis() - ms.lastFetchedAtMillis) < COALESCE_WINDOW_MS
            }
            if (recentlyFetched) {
                L.d { "[Call] CallServiceUrlManager fetch coalesced (already fetched within ${COALESCE_WINDOW_MS}ms)" }
                return@withLock synchronized(lock) { connectionEndpointsFromState(memState) }
            }
            doFetchAndCache()
        }
    }

    private suspend fun doFetchAndCache(): List<String> {
        val token = (globalServices.userManager.getUserData()?.microToken ?: "")
        if (token.isEmpty()) {
            return synchronized(lock) {
                loadFromDiskLocked()
                connectionEndpointsFromState(memState)
            }
        }
        try {
            val response = callHttpService.getServiceUrlV2(token)
            if (response.status != 0 || response.data == null) {
                L.e { "[Call] CallServiceUrlManager getServiceUrlV2 failed status=${response.status}" }
                statisticsLogManager.get().report(
                    CallStatisticsEvent.ConfigRefreshFail(
                        errorCode = response.status.toString(),
                        errorMsg = response.reason.orEmpty(),
                    )
                )
                return synchronized(lock) {
                    loadFromDiskLocked()
                    connectionEndpointsFromState(memState)
                }
            }
            val merged = mergeAndPersist(response.data!!, response.serverTimestamp)
            val su = merged.serviceUrls?.toServiceUrls()
            L.i {
                "[Call] CallServiceUrlManager cached serviceUrls version=${su?.config_version}, expiresAt=${merged.expiresAtMillis}"
            }
            return connectionEndpointsFromState(merged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e(e) { "[Call] CallServiceUrlManager fetch failed" }
            return synchronized(lock) {
                loadFromDiskLocked()
                connectionEndpointsFromState(memState)
            }
        }
    }

    /**
     * Atomically merges the remote response with the current [memState] and persists the result.
     *
     * The read-merge-persist sequence must happen under a single lock acquisition; otherwise two
     * concurrent refreshes (e.g. [onAppForegrounded] and [refreshAfterConnectionFailure]) can both
     * read the same pre-update snapshot, compute their own merges, and race on the final persist —
     * the later writer silently overwrites the earlier one with a stale-snapshot-based merge.
     */
    private fun mergeAndPersist(data: ServiceUrlDataV2, serverTimestamp: Long?): CallServiceUrlDiskState {
        val remote = data.serviceUrls
        val result = synchronized(lock) {
            loadFromDiskLocked()
            if (remote == null) {
                L.w { "[Call] CallServiceUrlManager serviceUrls body null" }
                memState ?: CallServiceUrlDiskState()
            } else {
                val merged = buildStateFromRemote(memState, remote, serverTimestamp)
                persistLocked(merged)
                merged
            }
        }
        return result
    }

    /** Current in-memory/disk [ServiceUrls] (no expiration check), used to read refresh results after failover. */
    fun getCachedServiceUrls(): ServiceUrls? {
        // Proxy active for calls: keep parity with ensureServiceUrlsForCall — read the
        // proxy call tunnel domains so failover paths don't fall back to live URLs.
        if (proxyConfigProviderLazy.get().isEnabledForCall) {
            return proxyServiceUrls()
        }
        return getUpstreamCachedServiceUrls()
    }

    /**
     * Raw persisted [ServiceUrls] straight from the disk/memory cache, WITHOUT the
     * proxy-synthesis short-circuit in [getCachedServiceUrls]. This returns the real
     * upstream servers (domain + IPs) returned by `serviceurl/v2`, regardless of the
     * "Protect IP address in calls" proxy toggle.
     *
     * Diagnostics only (the Insider "Call service settings" screen surfaces these so
     * the real servers are visible even while the proxy forces call connections onto
     * the tunnel domains). NEVER use this for connection routing — under the proxy,
     * calls must connect by tunnel domain, not by these IPs.
     */
    fun getUpstreamCachedServiceUrls(): ServiceUrls? {
        synchronized(lock) {
            loadFromDiskLocked()
            return memState?.serviceUrls?.toServiceUrls()
        }
    }

    companion object {
        private const val FAILURE_REFRESH_MIN_INTERVAL_MS = 30_000L
        private const val COALESCE_WINDOW_MS = 5_000L
        private val FALLBACK_HARDCODED_URLS = emptyList<String>()
    }
}
