package com.difft.android.network

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.network.config.GlobalConfigsManager
import dagger.Lazy
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一管理 HTTP 和 WebSocket 的 Host 切换逻辑
 *
 * 功能：
 * 1. 获取指定 serviceType 的 host，优先跳过上次失败的 host
 * 2. 记录失败的 host，持久化到 SharedPreferences
 * 3. 提供所有 hosts 列表供重试逻辑使用
 *
 * 注意：此类不处理默认值，由调用方（如 UrlManager）处理 fallback 逻辑
 */
@Singleton
class HostManager @Inject constructor(
    private val globalConfigsManager: Lazy<GlobalConfigsManager>
) {
    companion object {
        private const val SP_KEY_LAST_FAILED_HOSTS = "sp_last_failed_hosts"

        // ServiceType 常量（用于从 GlobalConfig 获取 hosts）
        const val SERVICE_TYPE_CHAT = "chat"

        // FailedHostKey 常量（用于区分 HTTP 和 WebSocket 的失败记录）
        const val FAILED_HOST_KEY_HTTP_CHAT = "http_chat"
        const val FAILED_HOST_KEY_WS_CHAT = "ws_chat"
    }

    // 内存缓存，避免频繁读取 SP
    private var cachedFailedHosts: Map<String, String>? = null

    private val newGlobalConfig by lazy {
        globalConfigsManager.get().getNewGlobalConfigs()
    }

    /**
     * 获取指定 serviceType 的 host，优先跳过上次失败的 host
     *
     * @param serviceType 服务类型，如 "chat"（用于从 GlobalConfig 获取 hosts 列表）
     * @param failedHostKey 失败记录 key，如 "http_chat" 或 "ws_chat"（用于区分不同连接类型的失败记录）
     * @return host 名称，如果没有配置则返回 null
     */
    fun getHost(serviceType: String, failedHostKey: String): String? {
        val hosts = getAllHosts(serviceType)
        if (hosts.isEmpty()) {
            return null
        }

        val lastFailed = getLastFailedHost(failedHostKey)
        if (lastFailed != null) {
            // 优先返回非失败的 host
            val nonFailedHost = hosts.firstOrNull { it != lastFailed }
            if (nonFailedHost != null) {
                return nonFailedHost
            }
        }
        // 只有一个 host 或找不到其他的，返回第一个
        return hosts.first()
    }

    /**
     * 获取指定 serviceType 的所有 hosts
     */
    fun getAllHosts(serviceType: String): List<String> {
        return newGlobalConfig?.data?.hosts
            ?.filter { it.servTo == serviceType }
            ?.mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }
            .orEmpty()
    }

    /**
     * 根据 hostName 查找对应的 serviceType
     */
    fun findServiceTypeByHost(hostName: String): String? {
        return newGlobalConfig?.data?.hosts?.find { it.name == hostName }?.servTo
    }

    /**
     * 记录失败的 host
     *
     * @param failedHostKey 失败记录 key，如 "http_chat" 或 "ws_chat"
     * @param hostName 失败的 host 名称
     */
    fun recordFailedHost(failedHostKey: String, hostName: String) {
        val currentMap = getFailedHostsMap().toMutableMap()
        currentMap[failedHostKey] = hostName
        saveFailedHostsMap(currentMap)
        L.i { "[Net] HostManager recordFailedHost: key=$failedHostKey, host=$hostName" }
    }

    /**
     * 获取指定 key 上次失败的 host
     */
    private fun getLastFailedHost(failedHostKey: String): String? {
        return getFailedHostsMap()[failedHostKey]
    }

    /**
     * 获取所有失败的 hosts Map（优先从缓存读取）
     */
    private fun getFailedHostsMap(): Map<String, String> {
        cachedFailedHosts?.let { return it }

        val jsonStr = SharedPrefsUtil.getString(SP_KEY_LAST_FAILED_HOSTS) ?: return emptyMap()
        return try {
            val jsonObject = JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            jsonObject.keys().forEach { key ->
                map[key] = jsonObject.getString(key)
            }
            cachedFailedHosts = map
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 保存失败的 hosts Map 到 SharedPreferences 并更新缓存
     */
    private fun saveFailedHostsMap(map: Map<String, String>) {
        cachedFailedHosts = map
        val jsonObject = JSONObject(map)
        SharedPrefsUtil.putString(SP_KEY_LAST_FAILED_HOSTS, jsonObject.toString())
    }
}