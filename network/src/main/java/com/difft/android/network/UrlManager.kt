package com.difft.android.network

import android.net.Uri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.network.config.GlobalConfigsManager
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
    override val inviteGroupUrl: String = "https://yelling.pro/"
    override val installationGuideUrl: String = "https://yelling.pro"
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
    override val inviteGroupUrl: String = "https://yelling.pro/"
    override val installationGuideUrl: String = "https://test.yelling.pro"
    override val appVersionConfigUrl: String = "https://d1u2vyihp77eo1.cloudfront.net/test-buildversion/insider-version.json"
}

class UrlManager @Inject constructor(
    val environmentHelper: EnvironmentHelper,
    private val globalConfigsManager: Lazy<GlobalConfigsManager>,
    private val coordinator: DomainSpeedTestCoordinator
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

    // ==================== HTTP ====================

    /**
     * Returns the best host (speed test -> persisted -> GlobalConfig -> hardcoded default).
     */
    private fun getBestHost(): String {
        return coordinator.getBestHostSync() ?: protocol.defaultHost
    }

    /**
     * Marks a host as unavailable for this session.
     */
    fun recordFailedHost(hostName: String) {
        coordinator.markHostUnavailable(hostName)
    }

    /**
     * Returns all hosts ranked by latency for retry fallback.
     */
    fun getAllHostsRanked(): List<String> {
        return coordinator.getAllHostsRanked()
    }

    val default: String
        get() {
            val host = getBestHost()
            return "https://$host/"
        }

    val chat: String
        get() {
            val host = getBestHost()
            val path = newGlobalConfig?.data?.srvs?.chat ?: "/chat"
            return "https://$host$path/"
        }

    val call: String
        get() {
            val host = getBestHost()
            val path = newGlobalConfig?.data?.srvs?.call ?: "/call"
            return "https://$host$path/"
        }

    val fileSharing: String
        get() {
            val host = getBestHost()
            val path = newGlobalConfig?.data?.srvs?.fileSharing ?: "/fileshare"
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
        val path = newGlobalConfig?.data?.srvs?.chat ?: "/chat"
        return "wss://$chatHost$path/v1/websocket/"
    }

    /**
     * Marks the last connected WebSocket host as unavailable; next connection will use another host.
     */
    fun switchToNextChatWebsocketHost() {
        val failedHost = lastConnectedWsHost ?: return
        coordinator.markHostUnavailable(failedHost)
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
            "https://yelling.pro/"
        ).mapNotNull { url ->
            Uri.parse(url).host
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