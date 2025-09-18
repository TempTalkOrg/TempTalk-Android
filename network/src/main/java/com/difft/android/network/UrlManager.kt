package com.difft.android.network

import android.net.Uri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.Host
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.network.config.GlobalConfigsManager
import dagger.Lazy
import javax.inject.Inject


private interface UrlProtocol {
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
    val defaultChatUrl = "https://chat.chative.im"

    override val default: String = defaultChatUrl
    override val chat: String = "$defaultChatUrl/chat/"
    override val call: String = "$defaultChatUrl/call/"
    override val fileSharing: String = "$defaultChatUrl/fileshare/"
    override val avatarStorage: String = "https://d272r1ud4wbyy4.cloudfront.net/"
    override val inviteGroupUrl: String = "https://temptalk.app/"
    override val installationGuideUrl: String = "https://temptalk.app/"

    override val appVersionConfigUrl: String = "https://d1u2vyihp77eo1.cloudfront.net/prod-buildversion/insider-version.json"
}

private class ChativeDevelopmentUrlProtocol : UrlProtocol {
    val defaultChatUrl = "https://chat.test.chative.im"

    override val default: String = defaultChatUrl

    override val chat: String = "$defaultChatUrl/chat/"
    override val call: String = "$defaultChatUrl/call/"
    override val fileSharing: String = "$defaultChatUrl/fileshare/"
    override val avatarStorage: String = "https://dtsgla5wj1qp2.cloudfront.net/"
    override val inviteGroupUrl: String = "https://temptalk.app/"
    override val installationGuideUrl: String = "https://temptalk.app/"

    override val appVersionConfigUrl: String = "https://d1u2vyihp77eo1.cloudfront.net/test-buildversion/insider-version.json"
}

class UrlManager @Inject constructor(
    val environmentHelper: EnvironmentHelper,
    private val globalConfigsManager: Lazy<GlobalConfigsManager>
) {

    companion object {
        private var hostPosition: Int = 0

        fun changeToOtherHost() {
            hostPosition += 1
            L.d { "[Net] changeToOtherHost:$hostPosition" }
        }
    }

    private val protocol: UrlProtocol = when {
        environmentHelper.isThatEnvironment(environmentHelper.ENVIRONMENT_ONLINE) -> ChativeOnlineUrlProtocol()
        environmentHelper.isThatEnvironment(environmentHelper.ENVIRONMENT_DEVELOPMENT) -> ChativeDevelopmentUrlProtocol()
        else -> throw IllegalArgumentException("Unknown Environment.")
    }


    private val newGlobalConfig by lazy {
        globalConfigsManager.get().getNewGlobalConfigs()
    }

    private fun findHost(serviceType: String): Host? {
        val hosts = newGlobalConfig?.data?.hosts?.filter { it.servTo == serviceType }
        if (!hosts.isNullOrEmpty()) {
            if (hostPosition >= hosts.size) {
                hostPosition = 0
            }
            return hosts[hostPosition]
        }
        return null
    }

    fun findHostsByOldHost(oldHostName: String): List<String> {
        val oldHost = newGlobalConfig?.data?.hosts?.find { it.name == oldHostName }
        return newGlobalConfig?.data?.hosts?.filter { it.servTo == oldHost?.servTo }?.mapNotNull { it.name } ?: emptyList()
    }

    val default: String
        get() {
            val host = findHost("chat")
            return if (host != null) {
                "https://${host.name}/"
            } else {
                protocol.default
            }
        }

    val chat: String
        get() {
            val host = findHost("chat")
            val path = newGlobalConfig?.data?.srvs?.chat
            return if (host != null && path != null) {
                "https://" + host.name + path + "/"
            } else {
                protocol.chat
            }
        }

    val call: String
        get() {
            val host = findHost("chat")
            val path = newGlobalConfig?.data?.srvs?.call
            return if (host != null && path != null) {
                "https://" + host.name + path + "/"
            } else {
                protocol.call
            }
        }
    val fileSharing: String
        get() {
            val host = findHost("chat")
            val path = newGlobalConfig?.data?.srvs?.fileSharing
            return if (host != null && path != null) {
                "https://" + host.name + path + "/"
            } else {
                protocol.fileSharing
            }
        }

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
        get() = newGlobalConfig?.data?.installationGuideUrl ?: protocol.installationGuideUrl

    val appVersionConfigUrl
        get() = protocol.appVersionConfigUrl

    fun isTrustedHost(host: String): Boolean {
        val trustedHosts = setOf(
            inviteGroupUrl,
            installationGuideUrl,
            default,
            "https://chative.com/",
            "https://temptalk.app/",
            "https://test.temptalk.app/"
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