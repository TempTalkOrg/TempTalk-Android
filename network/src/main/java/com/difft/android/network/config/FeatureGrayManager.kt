package com.difft.android.network.config

import android.annotation.SuppressLint
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.GrayCheckRequestBody
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object FeatureGrayManager {

    private const val GRAY_PREFS_KEY= "gray_map_json"
    const val FEATURE_GRAY_CALL_QUICK = "quic"
    private const val GRAY_CONFIG_UPDATE_INTERVAL: Long = 1000 * 60 * 5

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        @ChativeHttpClientModule.Chat
        fun httpClient(): ChativeHttpClient

        val userManager: UserManager
    }

    private val chatHttpClient by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(com.difft.android.base.utils.application).httpClient()
    }
    private val mutex = Mutex()
    private val grayCache = ConcurrentHashMap<String, Boolean>()


    suspend fun init() {
        // 从本地加载缓存
        mutex.withLock {
            grayCache.putAll(loadGrayMap())
        }
        // APP启动主动同步
        refreshFromServer(null)
    }

    /**
     * 灰度判断逻辑
     * - 默认 false（灰度关闭）
     * - 本地缓存优先
     */
    suspend fun isEnabled(source: String): Boolean {
        return mutex.withLock {
            grayCache[source] ?: false
        }
    }

    /**
     * sources = null → 获取全部灰度配置
     * sources = [...] → 获取指定灰度配置
     */
    @SuppressLint("CheckResult")
    suspend fun refreshFromServer(sources: List<String>?) {
        try {
            val resp = chatHttpClient.httpService.grayCheck(SecureSharedPrefsUtil.getToken(), GrayCheckRequestBody(sources))
            .subscribeOn(Schedulers.io())
            .await()

            if (resp.status != 0) {
                L.e { "[FeatureGrayManager] server returned status=${resp.status}" }
                return
            }

            val serverMap = resp.data?.associate { it.source to it.isInGray } ?: return

            // 确保线程安全
            mutex.withLock {
                if (sources.isNullOrEmpty()) {
                    // 全量刷新
                    grayCache.clear()
                    grayCache.putAll(serverMap)
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
        } catch (e: Exception) {
            L.e { "[FeatureGrayManager] gray update error: ${e.message}" }
        }
    }

    private fun saveGrayMap(map: Map<String, Boolean>) {
        SharedPrefsUtil.putString(GRAY_PREFS_KEY, JSONObject(map).toString())
    }

    private fun loadGrayMap(): Map<String, Boolean> {
        val json = SharedPrefsUtil.getString(GRAY_PREFS_KEY) ?: return emptyMap()
        return JSONObject(json).let { obj ->
            obj.keys().asSequence().associateWith { obj.getBoolean(it) }
        }
    }

    suspend fun checkUpdateConfigFromServer(lastUseTime: Long) {
        if(System.currentTimeMillis() - lastUseTime > GRAY_CONFIG_UPDATE_INTERVAL) {
            refreshFromServer(null)
        }
    }
}