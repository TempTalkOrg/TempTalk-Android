package com.difft.android.network

import androidx.core.net.toUri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import dagger.Lazy
import javax.inject.Inject


private interface UrlProtocol {
    val defaultHost: String
    val default: String
    val chat: String
    val call: String
    val fileSharing: String
    val avatarStorage: String
    val inviteGroupUrl: String
    val installationGuideUrl: String
    val appVersionConfigUrl: String
}

private class ChativeOnlineUrlProtocol : UrlProtocol {
    override val defaultHost = "chat.chative.im"
    private val defaultChatUrl = "https://$defaultHost"

    override val default: String = defaultChatUrl
    override val chat: String = "$defaultChatUrl/chat/"
    override val call: String = "$defaultChatUrl/call/"
    override val fileSharing: String = "$defaultChatUrl/fileshare/"
    override val avatarStorage: String = "https://d272r1ud4wbyy4.cloudfront.net/"
    override val inviteGroupUrl: String = "https://quicall.app/"
    override val installationGuideUrl: String = "https://quicall.app"
    override val appVersionConfigUrl: String = "https://d1u2vyihp77eo1.cloudfront.net/prod-buildversion/insider-version.json"
}

private class ChativeDevelopmentUrlProtocol : UrlProtocol {
    override val defaultHost = "chat.test.chative.im"
    private val defaultChatUrl = "https://$defaultHost"

    override val default: String = defaultChatUrl
    override val chat: String = "$defaultChatUrl/chat/"
    override val call: String = "$defaultChatUrl/call/"
    override val fileSharing: String = "$defaultChatUrl/fileshare/"
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

    private val newGlobalConfig by lazy {
        globalConfigsManager.get().getNewGlobalConfigs()
    }

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

    private fun chatPath(): String = newGlobalConfig?.data?.srvs?.chat ?: "/chat"

    private fun callPath(): String = newGlobalConfig?.data?.srvs?.call ?: "/call"

    private fun fileSharingPath(): String = newGlobalConfig?.data?.srvs?.fileSharing ?: "/fileshare"

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

    val chat: String
        get() {
            val host = getBestHost()
            val path = chatPath()
            return "https://$host$path/"
        }

    val call: String
        get() {
            val host = getBestHost()
            val path = callPath()
            return "https://$host$path/"
        }

    val fileSharing: String
        get() {
            val host = getBestHost()
            val path = fileSharingPath()
            return "https://$host$path/"
        }

    // ==================== WebSocket ====================

    /**
     * Returns the chat WebSocket URL using the best available host.
     * Also records the host for accurate failure reporting in [switchToNextChatWebsocketHost].
     */
    fun getChatWebsocketUrl(): String {
        val chatHost = getBestHost()
        lastConnectedWsHost = chatHost
        val path = chatPath()
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
        get() = newGlobalConfig?.data?.avatarFile ?: protocol.avatarStorage

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