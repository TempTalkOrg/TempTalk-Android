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
    // Test seams (#1395): unit tests have no Hilt graph for the entry point.
    @VisibleForTesting
    internal var userManagerProvider: () -> UserManager = { entryPoint.userManager }

    @VisibleForTesting
    internal var chatHttpClientProvider: () -> ChativeHttpClient = { entryPoint.httpClient() }

    private val chatHttpClient get() = chatHttpClientProvider()
    private val userManager get() = userManagerProvider()
    private val mutex = Mutex()
    private val grayCache = ConcurrentHashMap<String, Boolean>()

    // Whether the persisted gray map has reached grayCache this process; read and written under mutex.
    @Volatile
    private var localLoaded = false

    /** `elapsedRealtime` of the last successful server check; 0 means never (#1395). */
    @Volatile
    private var lastServerCheckAt = 0L

    /** Monotonic clock for the refresh throttle; replaceable in tests. */
    @VisibleForTesting
    internal var elapsedRealtime: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    suspend fun init() {
        // 从本地加载缓存
        mutex.withLock {
            ensureLocalLoaded()
        }
        // APP启动主动同步
        if (refreshFromServer(null)) lastServerCheckAt = elapsedRealtime()
    }

    /**
     * 灰度判断逻辑
     * - 默认 false（灰度关闭）
     * - 本地缓存优先
     *
     * The first read loads the persisted map itself, so a process woken in the background (an
     * incoming call before IndexActivity ran [init]) still sees it (#1395).
     */
    suspend fun isEnabled(source: String): Boolean {
        return mutex.withLock {
            ensureLocalLoaded()
            grayCache[source] ?: false
        }
    }

    // Caller holds mutex. Server values already in grayCache win over the persisted ones; with no
    // user yet, the load waits for a later read or init() after login.
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

    /**
     * sources = null → 获取全部灰度配置
     * sources = [...] → 获取指定灰度配置
     *
     * Returns true when a server map was received and applied.
     */
    suspend fun refreshFromServer(sources: List<String>?): Boolean {
        return try {
            // Login-state gate: with no persisted session token there is nothing to check for.
            if (userManager.getUserData()?.microToken.isNullOrEmpty()) return false
            val resp = chatHttpClient.httpService.grayCheck(GrayCheckRequestBody(sources))

            if (resp.status != 0) {
                L.e { "[FeatureGrayManager] server returned status=${resp.status}" }
                return false
            }

            val serverMap = resp.data?.associate { it.source to it.isInGray } ?: return false

            // 确保线程安全
            mutex.withLock {
                if (sources.isNullOrEmpty()) {
                    // 全量刷新
                    grayCache.clear()
                    grayCache.putAll(serverMap)
                    // The full server map replaces (and is persisted over) the local one.
                    localLoaded = true
                } else {
                    // 部分刷新
                    serverMap.forEach { (k, v) -> grayCache[k] = v }
                    // 服务端未返回的 → 默认 false
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

    /**
     * Refreshes from the server at most once per [GRAY_CONFIG_UPDATE_INTERVAL] since the last
     * successful check, on a monotonic process clock; a failed check is not stamped, so the next
     * foreground retries (#1395).
     */
    suspend fun checkUpdateConfigFromServer() {
        val last = lastServerCheckAt
        if (last != 0L && elapsedRealtime() - last < GRAY_CONFIG_UPDATE_INTERVAL) return
        if (refreshFromServer(null)) lastServerCheckAt = elapsedRealtime()
    }
}