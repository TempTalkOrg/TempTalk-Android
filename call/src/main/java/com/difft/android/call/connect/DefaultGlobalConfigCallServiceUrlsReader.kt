package com.difft.android.call.connect

import android.content.Context
import com.difft.android.base.call.ServiceUrls
import com.difft.android.base.call.UrlInfo
import com.difft.android.base.log.lumberjack.L
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses `data.call.callServers.serviceUrls` from the assets [DEFAULT_GLOBAL_CONFIG] file.
 */
object DefaultGlobalConfigCallServiceUrlsReader {

    private const val DEFAULT_GLOBAL_CONFIG = "default_global_config.json"

    fun read(context: Context): ServiceUrls? {
        return try {
            val text = context.assets.open(DEFAULT_GLOBAL_CONFIG).bufferedReader().use { it.readText() }
            parse(text)
        } catch (e: Exception) {
            L.w(e) { "[Call] DefaultGlobalConfigCallServiceUrlsReader read failed" }
            null
        }
    }

    internal fun parse(jsonText: String): ServiceUrls? {
        val root = JSONObject(jsonText)
        val data = root.optJSONObject("data") ?: return null
        val call = data.optJSONObject("call") ?: return null
        val servers = call.optJSONObject("callServers") ?: return null
        val su = servers.optJSONObject("serviceUrls") ?: return null
        val primary = su.optJSONObject("primary")?.let { parseUrlInfo(it) } ?: return null
        val fallbackArr: JSONArray = su.optJSONArray("fallback") ?: JSONArray()
        val fallback = (0 until fallbackArr.length()).map { i ->
            fallbackArr.optJSONObject(i)?.let { parseUrlInfo(it) }
        }
        val configVersion = su.optInt("config_version", 0)
        val ttl = su.optInt("ttl", 3600)
        return ServiceUrls(
            config_version = configVersion,
            fallback = fallback,
            primary = primary,
            ttl = ttl,
        )
    }

    private fun parseUrlInfo(obj: JSONObject): UrlInfo {
        val addrsList = mutableListOf<String>()
        val addrsArr = obj.optJSONArray("addrs")
        if (addrsArr != null) {
            for (i in 0 until addrsArr.length()) {
                addrsArr.optString(i)?.trim()?.takeIf { it.isNotEmpty() }?.let { addrsList += it }
            }
        }
        return UrlInfo(
            addrs = addrsList,
            domain = obj.optString("domain", "").trim(),
            region = obj.optString("region", "").trim(),
        )
    }
}
