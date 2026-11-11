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






@Singleton
class CallServiceUrlManager @Inject constructor(
    private val statisticsLogManager: dagger.Lazy<CallStatisticsLogManager>,
    @ChativeHttpClientModule.Call private val callHttpClient: dagger.Lazy<ChativeHttpClient>,
    private val secureConfigStore: SecureConfigStore,
    private val proxyConfigProviderLazy: dagger.Lazy<ProxyConfigProvider>,
    private val globalConfigsManagerLazy: dagger.Lazy<IGlobalConfigsManager>,
    private val physicalHttpTransport: dagger.Lazy<CallPhysicalHttpTransport>,
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

    





    @Volatile
    private var lastForegroundAttemptAtMillis: Long = 0L

    private val foregroundRefreshMinIntervalMs = 20 * 60 * 1000L

    












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

    




    fun onAppForegrounded() {
        appScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val lastFetch = synchronized(lock) {
                loadFromDiskLocked()
                memState?.lastFetchedAtMillis ?: 0L
            }
            
            
            
            
            val throttleRef = maxOf(lastFetch, lastForegroundAttemptAtMillis)
            if (now - throttleRef < foregroundRefreshMinIntervalMs) return@launch
            lastForegroundAttemptAtMillis = now
            fetchCallServiceUrlAndCache()
        }
    }

    





    suspend fun ensureServiceUrlsForCall(timeoutMs: Long = 15_000L): ServiceUrls? {
        
        
        
        
        
        
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
        
        if (globalServices.userManager.getUserData()?.microToken.isNullOrEmpty()) {
            return synchronized(lock) {
                loadFromDiskLocked()
                connectionEndpointsFromState(memState)
            }
        }
        try {
            val response = if (proxyConfigProviderLazy.get().isEnabledForCall) {
                callHttpService.getServiceUrlV2()
            } else {
                try {
                    physicalHttpTransport.get().fetchServiceUrls()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.e(e) {
                        "[Call] physical service URL request failed, falling back to Retrofit"
                    }
                    callHttpService.getServiceUrlV2()
                }
            }
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

    
    fun getCachedServiceUrls(): ServiceUrls? {
        
        
        if (proxyConfigProviderLazy.get().isEnabledForCall) {
            return proxyServiceUrls()
        }
        return getUpstreamCachedServiceUrls()
    }

    










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
