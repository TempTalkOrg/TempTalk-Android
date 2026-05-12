package com.difft.android.call.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.difft.android.base.call.ServiceUrlDataV2
import com.difft.android.base.call.ServiceUrls
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    @ApplicationContext private val appContext: Context,
) {

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        @ChativeHttpClientModule.Call
        fun callHttpClient(): ChativeHttpClient
    }

    private val callHttpService: LCallHttpService by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(ApplicationHelper.instance)
            .callHttpClient()
            .getService(LCallHttpService::class.java)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs: SharedPreferences by lazy {
        val start = System.currentTimeMillis()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE_NAME,
            MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also {
            L.i { "[Call] CallServiceUrlManager EncryptedSharedPreferences init took ${System.currentTimeMillis() - start}ms" }
        }
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

    private fun loadFromDiskLocked() {
        if (loadedFromDisk) return
        val raw = prefs.getString(PREFS_KEY_STATE, null)
        memState = if (raw.isNullOrBlank()) {
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

    private fun persistLocked(state: CallServiceUrlDiskState) {
        prefs.edit { putString(PREFS_KEY_STATE, json.encodeToString(state)) }
        memState = state
    }

    private fun mergeWithRemote(
        local: CallServiceUrlDiskState?,
        remote: ServiceUrls,
        serverTimestamp: Long?,
    ): CallServiceUrlDiskState {
        val remoteVersion = remote.config_version
        val localVersion = local?.configVersion() ?: -1
        val now = System.currentTimeMillis()
        val expiresAt = computeExpiresAtMillis(serverTimestamp, remote.ttl)

        return when {
            localVersion > remoteVersion -> {
                L.w {
                    "[Call] CallServiceUrlManager remote config_version ($remoteVersion) < local ($localVersion), keep local"
                }
                local?.copy(
                    expiresAtMillis = expiresAt,
                    lastFetchedAtMillis = now,
                ) ?: CallServiceUrlDiskState(
                    expiresAtMillis = expiresAt,
                    lastFetchedAtMillis = now,
                    serviceUrls = remote.toStored(),
                )
            }
            localVersion == remoteVersion && local != null -> {
                local.copy(
                    expiresAtMillis = expiresAt,
                    lastFetchedAtMillis = now,
                )
            }
            else -> {
                CallServiceUrlDiskState(
                    expiresAtMillis = expiresAt,
                    lastFetchedAtMillis = now,
                    serviceUrls = remote.toStored(),
                )
            }
        }
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
     * throttle check itself touches disk: [loadFromDiskLocked] lazily initializes
     * [EncryptedSharedPreferences], which performs Android Keystore load + AES256_GCM master key
     * derivation on first access (see the init-took log below) and subsequently a GCM-decrypted
     * `prefs.getString(...)`. Keeping this off main avoids cold-start jank / potential ANR.
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
        val token = SecureSharedPrefsUtil.getToken()
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
        return synchronized(lock) {
            loadFromDiskLocked()
            if (remote == null) {
                L.w { "[Call] CallServiceUrlManager serviceUrls body null" }
                memState ?: CallServiceUrlDiskState()
            } else {
                val merged = mergeWithRemote(memState, remote, serverTimestamp)
                persistLocked(merged)
                merged
            }
        }
    }

    /** Current in-memory/disk [ServiceUrls] (no expiration check), used to read refresh results after failover. */
    fun getCachedServiceUrls(): ServiceUrls? {
        synchronized(lock) {
            loadFromDiskLocked()
            return memState?.serviceUrls?.toServiceUrls()
        }
    }

    companion object {
        private const val PREFS_FILE_NAME = "secure_global_config"
        private const val PREFS_KEY_STATE = "call_service_url_state_v3"

        private const val FAILURE_REFRESH_MIN_INTERVAL_MS = 30_000L
        private const val COALESCE_WINDOW_MS = 5_000L
        private val FALLBACK_HARDCODED_URLS = emptyList<String>()
    }
}
