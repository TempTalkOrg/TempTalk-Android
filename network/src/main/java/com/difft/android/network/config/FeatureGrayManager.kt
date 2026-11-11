package com.difft.android.network.config

import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.GrayCheckRequestBody
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object FeatureGrayManager {

    const val FEATURE_GRAY_CALL_QUICK = "quic"
    private const val GRAY_CONFIG_UPDATE_INTERVAL: Long = 1000 * 60 * 5

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        @ChativeHttpClientModule.Chat
        fun httpClient(): ChativeHttpClient

        val userManager: UserManager
    }

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(com.difft.android.base.utils.application)
    }
    
    @VisibleForTesting
    internal var userManagerProvider: () -> UserManager = { entryPoint.userManager }

    @VisibleForTesting
    internal var chatHttpClientProvider: () -> ChativeHttpClient = { entryPoint.httpClient() }

    private val chatHttpClient get() = chatHttpClientProvider()
    private val userManager get() = userManagerProvider()
    private val mutex = Mutex()
    private val grayCache = ConcurrentHashMap<String, Boolean>()

    
    @Volatile
    private var localLoaded = false

    
    @Volatile
    private var lastServerCheckAt = 0L

    
    @VisibleForTesting
    internal var elapsedRealtime: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    suspend fun init() {
        mutex.withLock {
            ensureLocalLoaded()
        }
        if (refreshFromServer(null)) lastServerCheckAt = elapsedRealtime()
    }

    



    suspend fun isEnabled(source: String): Boolean {
        return mutex.withLock {
            ensureLocalLoaded()
            grayCache[source] ?: false
        }
    }

    
    
    private fun ensureLocalLoaded() {
        if (localLoaded) return
        if (userManager.getUserData() == null) return
        try {
            loadGrayMap().forEach { (k, v) -> grayCache.putIfAbsent(k, v) }
        } catch (e: Exception) {
            L.w { "[FeatureGrayManager] local gray map load failed ${e.javaClass.simpleName}" }
        }
        localLoaded = true
    }

    @VisibleForTesting
    internal fun resetForTest() {
        grayCache.clear()
        localLoaded = false
        lastServerCheckAt = 0L
    }

    



    suspend fun refreshFromServer(sources: List<String>?): Boolean {
        return try {
            
            if (userManager.getUserData()?.microToken.isNullOrEmpty()) return false
            val resp = chatHttpClient.httpService.grayCheck(GrayCheckRequestBody(sources))

            if (resp.status != 0) {
                L.e { "[FeatureGrayManager] server returned status=${resp.status}" }
                return false
            }

            val serverMap = resp.data?.associate { it.source to it.isInGray } ?: return false

            mutex.withLock {
                if (sources.isNullOrEmpty()) {
                    grayCache.clear()
                    grayCache.putAll(serverMap)
                    
                    localLoaded = true
                } else {
                    serverMap.forEach { (k, v) -> grayCache[k] = v }
                    
                    sources.forEach { s ->
                        if (!serverMap.containsKey(s)) {
                            grayCache[s] = false
                        }
                    }
                }
                saveGrayMap(grayCache)
            }
            L.i { "[FeatureGrayManager] gray update success: $grayCache" }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[FeatureGrayManager] gray update failed ${e.javaClass.simpleName}: ${e.message}" }
            false
        }
    }

    private fun saveGrayMap(map: Map<String, Boolean>) {
        userManager.update { grayMapJson = JSONObject(map).toString() }
    }

    private fun loadGrayMap(): Map<String, Boolean> {
        val json = userManager.getUserData()?.grayMapJson?.takeIf { it.isNotEmpty() }
            ?: return emptyMap()
        return JSONObject(json).let { obj ->
            obj.keys().asSequence().associateWith { obj.getBoolean(it) }
        }
    }

    




    suspend fun checkUpdateConfigFromServer() {
        val last = lastServerCheckAt
        if (last != 0L && elapsedRealtime() - last < GRAY_CONFIG_UPDATE_INTERVAL) return
        if (refreshFromServer(null)) lastServerCheckAt = elapsedRealtime()
    }
}
