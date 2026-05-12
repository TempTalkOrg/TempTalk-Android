package com.difft.android.call.manager

import com.difft.android.base.call.ServiceUrls
import com.difft.android.base.call.UrlInfo
import kotlinx.serialization.Serializable

/**
 * Encrypted disk + in-memory snapshot of call service configuration (complete [ServiceUrls], not flat URLs).
 */
@Serializable
internal data class CallServiceUrlDiskState(
    val expiresAtMillis: Long = 0L,
    val lastFetchedAtMillis: Long = 0L,
    val serviceUrls: StoredServiceUrls? = null,
) {
    fun configVersion(): Int = serviceUrls?.config_version ?: -1

    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        serviceUrls == null || nowMillis >= expiresAtMillis
}

@Serializable
internal data class StoredServiceUrls(
    val config_version: Int,
    val fallback: List<StoredUrlInfo?>,
    val primary: StoredUrlInfo?,
    val ttl: Int,
)

@Serializable
internal data class StoredUrlInfo(
    val addrs: List<String>,
    val domain: String,
    val region: String,
)

internal fun ServiceUrls.toStored(): StoredServiceUrls = StoredServiceUrls(
    config_version = config_version,
    fallback = fallback.map { it?.toStored() },
    primary = primary?.toStored(),
    ttl = ttl,
)

internal fun UrlInfo.toStored(): StoredUrlInfo = StoredUrlInfo(
    addrs = addrs,
    domain = domain,
    region = region,
)

internal fun StoredServiceUrls.toServiceUrls(): ServiceUrls = ServiceUrls(
    config_version = config_version,
    fallback = fallback.map { it?.toUrlInfo() },
    primary = primary?.toUrlInfo(),
    ttl = ttl,
)

internal fun StoredUrlInfo.toUrlInfo(): UrlInfo = UrlInfo(
    addrs = addrs,
    domain = domain,
    region = region,
)

/**
 * Absolute expiration time: serverTimestamp (seconds or milliseconds) + ttl (seconds).
 */
internal fun computeExpiresAtMillis(serverTimestamp: Long?, ttlSeconds: Int): Long {
    val ttlMs = ttlSeconds.coerceAtLeast(0).toLong() * 1000L
    val st = serverTimestamp
    val baseMillis = when {
        st == null -> System.currentTimeMillis()
        st > 1_000_000_000_000L -> st
        else -> st * 1000L
    }
    return baseMillis + ttlMs
}
