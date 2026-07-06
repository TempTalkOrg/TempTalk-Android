package com.difft.android.network

import androidx.core.net.toUri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.Data
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import dagger.Lazy
import javax.inject.Inject


private interface UrlProtocol {
    val defaultHost: String
    val avatarStorage: String
    val inviteGroupUrl: String
    val installationGuideUrl: String
    val appVersionConfigUrl: String
}

private class ChativeOnlineUrlProtocol : UrlProtocol {
    override val defaultHost = "chat.chative.im"
    override val avatarStorage: String = "https://d272r1ud4wbyy4.cloudfront.net/"
    override val inviteGroupUrl: String = "https://quicall.app/"
    override val installationGuideUrl: String = "https://quicall.app"
    override val appVersionConfigUrl: String = "https://d1u2vyihp77eo1.cloudfront.net/prod-buildversion/insider-version.json"
}

private class ChativeDevelopmentUrlProtocol : UrlProtocol {
    override val defaultHost = "chat.test.chative.im"
    override val avatarStorage: String = "https://dtsgla5wj1qp2.cloudfront.net/"
    override val inviteGroupUrl: String = "https://quicall.app/"
    override val installationGuideUrl: String = "https://quicall.app"
    override val appVersionConfigUrl: String = "https://d1u2vyihp77eo1.cloudfront.net/test-buildversion/insider-version.json"
}

class UrlManager @Inject constructor(
    val environmentHelper: EnvironmentHelper,
    private val globalConfigsManager: Lazy<GlobalConfigsManager>,
    private val coordinator: Lazy<DomainSpeedTestCoordinator>,
    private val proxyConfigProvider: Lazy<ProxyConfigProvider>,
) {

    private val protocol: UrlProtocol = when {
        environmentHelper.isThatEnvironment(environmentHelper.ENVIRONMENT_ONLINE) -> ChativeOnlineUrlProtocol()
        environmentHelper.isThatEnvironment(environmentHelper.ENVIRONMENT_DEVELOPMENT) -> ChativeDevelopmentUrlProtocol()
        else -> throw IllegalArgumentException("Unknown Environment.")
    }

    /** Tracks the host used by the last WebSocket connection, for accurate failure reporting. */
    @Volatile
    private var lastConnectedWsHost: String? = null

    /**
     * Reads the live global config [Data] fresh on every call (NOT cached in a
     * `by lazy`). The previous `by lazy` was a `@Singleton`-scoped freeze: it
     * pinned service paths to the config snapshot present at DI time, so a live
     * config refresh would move `getBestHost()` (real-time) while paths stayed
     * frozen → `https://{liveHost}{frozenPath}/` could route 404. Reading fresh
     * removes that permanent freeze so the path tracks the live config. It does
     * NOT make host (from the speed-test coordinator) and path (from config) a
     * single atomic read — a config push landing between the two reads inside
     * [serviceUrl] can transiently pair a new host with an old path, self-correcting
     * on the next access. The underlying manager caches by config identity, so
     * this stays O(1) in steady state.
     */
    private fun configData(): Data? = globalConfigsManager.get().getNewGlobalConfigs()?.data

    /**
     * Chat-module proxy tunnel domains (`proxy.tunnelDomains.chat`, live-preferred
     * + embedded fallback). Used only while the proxy is active, to force every
     * request onto this domain set (kept in sync with the relay's `ssl_preread`
     * whitelist), bypassing speed-test / persisted / live-config host selection.
     *
     * Read fresh (NOT cached in a `by lazy`) so a live config update to
     * `proxy.tunnelDomains.chat` is picked up without an app restart; the
     * underlying [GlobalConfigsManager.getProxyTunnelChatDomains] caches by
     * config identity, so the call is O(1) in the steady state.
     */
    private fun proxyChatDomains(): List<String> =
        globalConfigsManager.get().getProxyTunnelChatDomains()

    /**
     * Whether requests must be forced onto the embedded assets config. Reads the
     * injected instance (NOT the static [ProxyConfigProvider.isProxyActive]) so
     * [com.difft.android.network.proxy.ProxyConfigProvider.refreshFromUserDataIfChanged]
     * runs and self-heals the cold-start window where the static snapshot may
     * still be stale before `UserManager.warmUp()`.
     */
    private fun isProxyForced(): Boolean = proxyConfigProvider.get().isEnabled

    // ==================== HTTP ====================

    /**
     * Returns the best host. When the proxy is active, forces the embedded
     * assets host set (deterministic, whitelist-aligned) and rotates to the next
     * embedded host that hasn't been marked unavailable this session, so WS/HTTP
     * failover still works. Otherwise: speed test -> persisted -> GlobalConfig ->
     * hardcoded default.
     */
    private fun getBestHost(): String {
        if (isProxyForced()) {
            val domains = proxyChatDomains()
            return coordinator.get().firstAvailableHost(domains)
                ?: domains.firstOrNull()
                ?: protocol.defaultHost
        }
        return coordinator.get().getBestHostSync() ?: protocol.defaultHost
    }

    /**
     * The host every HTTP request must be pinned to while the proxy is
     * active (from `proxy.tunnelDomains.chat`), or `null` when the proxy is off
     * (caller leaves the request host untouched).
     *
     * Rationale: the `@Singleton` `ChativeHttpClient`s bake their Retrofit
     * `baseUrl` host at construction (app start, proxy usually OFF), and a runtime
     * proxy toggle does NOT rebuild them — so without a per-request rewrite HTTP
     * would keep hitting the startup host instead of the
     * whitelist-aligned proxy domain. [ProxyHostInterceptor] applies this. Mirrors
     * [getChatWebsocketUrl]'s host selection (same [getBestHost]) so HTTP and WS
     * stay on the same embedded host, including failover rotation.
     */
    fun proxyForcedHostOrNull(): String? = if (isProxyForced()) getBestHost() else null

    // Service paths come from the live GlobalConfig regardless of proxy state: the
    // path rides inside the encrypted connection and is NOT part of the host-based
    // tunnel/whitelist decision, so it has no IP-leak relevance. Pinning it to the
    // embedded bundle would only risk a proxy-only breakage if the server ever
    // changes a path. Only the HOST is forced to the embedded set (see getBestHost).

    /**
     * Resolves a service path from the live config via [ServiceUrlResolver.resolvePath]
     * (`services` + `domains` model), falling back to the [default] end string when
     * unresolved or blank. Path-only — independent of host-label resolution, so a
     * server-configured path is honored even if its domains don't resolve. 2-tier
     * (resolver → default); the legacy `srvs` is no longer read here.
     */
    private fun servicePath(name: String, default: String): String =
        ServiceUrlResolver.resolvePath(configData(), name)?.takeIf { it.isNotBlank() } ?: default

    /**
     * Marks a host as unavailable for this session.
     */
    fun recordFailedHost(hostName: String) {
        coordinator.get().markHostUnavailable(hostName)
    }

    /**
     * Returns all hosts ranked by latency for retry fallback. While the proxy is
     * active, returns the embedded assets host set so retries also stay on the
     * whitelist-aligned hosts instead of speed-test results.
     */
    fun getAllHostsRanked(): List<String> {
        if (isProxyForced()) {
            return proxyChatDomains().ifEmpty { listOf(protocol.defaultHost) }
        }
        return coordinator.get().getAllHostsRanked()
    }

    val default: String
        get() {
            val host = getBestHost()
            return "https://$host/"
        }

    /**
     * Builds a service base URL `https://{getBestHost()}{servicePath}/`. Shared by
     * the chat/call/fileSharing getters so host selection + concatenation live in
     * one place (the getters differ only by service name + default path).
     */
    private fun serviceUrl(name: String, defaultPath: String): String =
        "https://${getBestHost()}${servicePath(name, defaultPath)}/"

    val chat: String
        get() = serviceUrl(ServiceUrlResolver.SERVICE_NAME_CHAT, "/chat")

    val call: String
        get() = serviceUrl(ServiceUrlResolver.SERVICE_NAME_CALL, "/call")

    val fileSharing: String
        get() = serviceUrl(ServiceUrlResolver.SERVICE_NAME_FILE_SHARING, "/fileshare")

    /**
     * GIF proxy base URL. GIF trending/search ride the server `/gifs/` proxy (masks
     * client IP / search terms from GIPHY). `gifs` is a first-class entry in the live
     * `services` config (path `/gifs`, sharing the chat domain), so it resolves through
     * [serviceUrl] like chat/call/fileSharing: host from the speed-ranked/proxy host,
     * path from live config (default `/gifs`). Endpoints declare relative paths
     * (`v1/gifs/trending`) that resolve under this base.
     */
    val gifs: String
        get() = serviceUrl(ServiceUrlResolver.SERVICE_NAME_GIFS, "/gifs")

    // ==================== WebSocket ====================

    /**
     * Returns the chat WebSocket URL using the best available host.
     * Also records the host for accurate failure reporting in [switchToNextChatWebsocketHost].
     */
    fun getChatWebsocketUrl(): String {
        val chatHost = getBestHost()
        lastConnectedWsHost = chatHost
        val path = servicePath(ServiceUrlResolver.SERVICE_NAME_CHAT, "/chat")
        return "wss://$chatHost$path/v1/websocket/"
    }

    /**
     * Marks the last connected WebSocket host as unavailable; next connection will use another host.
     */
    fun switchToNextChatWebsocketHost() {
        val failedHost = lastConnectedWsHost ?: return
        coordinator.get().markHostUnavailable(failedHost)
        L.i { "[Net] UrlManager switchToNextChatWebsocketHost: marked unavailable host=$failedHost" }
    }

    // ==================== Other ====================

    private val avatarStorage
        get() = configData()?.avatarFile ?: protocol.avatarStorage

    val inviteGroupUrl
        get() = protocol.inviteGroupUrl

    fun getAvatarStorageUrl(attachmentId: String): String {
        return if (avatarStorage.endsWith("/")) {
            "${avatarStorage}$attachmentId"
        } else {
            "${avatarStorage}/$attachmentId"
        }
    }

    val installationGuideUrl
        get() = protocol.installationGuideUrl

    val appVersionConfigUrl
        get() = protocol.appVersionConfigUrl

    fun isTrustedHost(host: String): Boolean {
        val trustedHosts = setOf(
            inviteGroupUrl,
            installationGuideUrl,
            default,
            "https://chative.com/",
            "https://temptalk.app/",
            "https://test.temptalk.app/",
            "https://yelling.pro/",
            "https://quicall.app/"
        ).mapNotNull { url ->
            url.toUri().host
        }

        return trustedHosts.any { trustHost ->
            trustHost.equals(host, ignoreCase = true)
        }
    }

    // 联系人邀请链接
    // 旧版 https://chative.com/u/index.html?a=pi&pi=UydwUJeMeXt3aIsSD0qGLOQNF96Wxz0K
    // 新版 https://temptalk.app/u?pi=TqP1diDA
    fun isInviteLinkUrl(url: String): Boolean {
        val regex = Regex("^https://[^/]+/u[^?]*\\?([^#&]*&)*pi=[A-Za-z0-9]+.*$")
        return regex.matches(url)
    }

    //群邀请链接 https://www.test.chative.im/u/g.html?i=SoLSt0G8
    fun isGroupInviteLinkUrl(url: String): Boolean {
        val regex = Regex("^https://[^/]+/u[^?]*\\?([^#&]*&)*i=[A-Za-z0-9]+.*$")
        return regex.matches(url)
    }
}