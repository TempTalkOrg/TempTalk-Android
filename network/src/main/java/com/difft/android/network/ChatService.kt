package com.difft.android.network

/**
 * The chat-pool service a [ChativeHttpClient] talks to. Every service shares one host pool
 * ([UrlManager.currentHostFor]) and differs only in its path prefix ([UrlManager.servicePathFor]).
 *
 * A client built with a null service is not part of the pool: no host routing, no failover (#1396).
 */
enum class ChatService(
    /** `services[].name` in the live global config, or null when the service has no config entry. */
    internal val configName: String?,
    /** Path used when the live config carries none; empty for root-relative APIs. */
    internal val defaultPath: String,
) {
    CHAT(ServiceUrlResolver.SERVICE_NAME_CHAT, "/chat"),
    CALL(ServiceUrlResolver.SERVICE_NAME_CALL, "/call"),
    FILE_SHARING(ServiceUrlResolver.SERVICE_NAME_FILE_SHARING, "/fileshare"),

    /** Root-relative APIs on the bare host, with no service path to replace. */
    DEFAULT(null, ""),
}

/**
 * Request tag set by [ChatPoolRoutingInterceptor]: this call was routed inside the chat pool.
 * It only enables failover ([HostFailoverInterceptor]); failure attribution uses the host the
 * request is actually sent to.
 */
internal data class RoutedHost(val host: String, val service: ChatService)
