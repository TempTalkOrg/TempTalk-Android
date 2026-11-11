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
    val installationGuideUrl: String
    val appVersionConfigUrl: String
    val e2eeLearnMoreUrl: String
}

private class ChativeOnlineUrlProtocol : UrlProtocol {
    override val defaultHost = "chat.chative.im"
    override val avatarStorage: String = "https://d272r1ud4wbyy4.cloudfront.net/"
    override val installationGuideUrl: String = "https://quicall.app"
    override val appVersionConfigUrl: String = "https://d1u2vyihp77eo1.cloudfront.net/tt-prod-buildversion/insider-version.json"
    override val e2eeLearnMoreUrl: String = "https://quicall.app/security"
}

private class ChativeDevelopmentUrlProtocol : UrlProtocol {
    override val defaultHost = "chat.test.chative.im"
    override val avatarStorage: String = "https://dtsgla5wj1qp2.cloudfront.net/"
    override val installationGuideUrl: String = "https://quicall.app"
    override val appVersionConfigUrl: String = "https://d1u2vyihp77eo1.cloudfront.net/tt-test-buildversion/insider-version.json"
    override val e2eeLearnMoreUrl: String = "https://quicall.app/security"
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

    
    @Volatile
    private var lastConnectedWsHost: String? = null

    




    private fun configData(): Data? = globalConfigsManager.get().getNewGlobalConfigs()?.data

    










    private fun proxyChatDomains(): List<String> =
        globalConfigsManager.get().getProxyTunnelChatDomains()

    






    private fun isProxyForced(): Boolean = proxyConfigProvider.get().isEnabled

    

    






    private fun getBestHost(): String {
        if (isProxyForced()) {
            val domains = proxyChatDomains()
            return coordinator.get().firstAvailableHost(domains)
                ?: domains.firstOrNull()
                ?: protocol.defaultHost
        }
        return coordinator.get().getBestHostSync() ?: protocol.defaultHost
    }

    











    @Suppress("UNUSED_PARAMETER")
    fun currentHostFor(service: ChatService): String = getBestHost()

    
    

    






    private fun servicePath(name: String, default: String): String =
        ServiceUrlResolver.resolvePath(configData(), name)?.takeIf { it.isNotBlank() } ?: default

    



    fun servicePathFor(service: ChatService): String {
        val name = service.configName ?: return service.defaultPath
        return servicePath(name, service.defaultPath).trimEnd('/')
    }

    



    fun recordFailedHost(hostName: String) {
        coordinator.get().markHostUnavailable(hostName)
    }

    
    fun isChatPoolHost(host: String): Boolean = coordinator.get().isInPool(host)

    
    fun isNetworkAvailable(): Boolean = coordinator.get().isNetworkAvailable()

    
    fun recordHealthyHost(hostName: String) {
        coordinator.get().markHostHealthy(hostName)
    }

    




    fun getAllHostsRanked(): List<String> {
        if (isProxyForced()) {
            val domains = proxyChatDomains()
            if (domains.isEmpty()) return listOf(protocol.defaultHost)
            return coordinator.get().eligibleHosts(domains)
        }
        return coordinator.get().getAllHostsRanked()
    }

    val default: String
        get() {
            val host = getBestHost()
            return "https://$host/"
        }

    




    private fun serviceUrl(name: String, defaultPath: String): String =
        "https://${getBestHost()}${servicePath(name, defaultPath)}/"

    private fun serviceUrl(service: ChatService): String =
        serviceUrl(service.configName.orEmpty(), service.defaultPath)

    val chat: String
        get() = serviceUrl(ChatService.CHAT)

    val call: String
        get() = serviceUrl(ChatService.CALL)

    val fileSharing: String
        get() = serviceUrl(ChatService.FILE_SHARING)

    







    val gifs: String
        get() = serviceUrl(ServiceUrlResolver.SERVICE_NAME_GIFS, "/gifs")

    

    



    fun getChatWebsocketUrl(): String {
        val chatHost = getBestHost()
        lastConnectedWsHost = chatHost
        val path = servicePath(ServiceUrlResolver.SERVICE_NAME_CHAT, "/chat")
        return "wss://$chatHost$path/v1/websocket/"
    }

    



    fun switchToNextChatWebsocketHost() {
        val failedHost = lastConnectedWsHost ?: return
        coordinator.get().markHostUnavailable(failedHost)
        L.i { "[Net] UrlManager switchToNextChatWebsocketHost: host=$failedHost" }
    }

    
    fun recordChatWebsocketConnected() {
        lastConnectedWsHost?.let { coordinator.get().markHostHealthy(it) }
    }

    

    private val avatarStorage
        get() = configData()?.avatarFile ?: protocol.avatarStorage

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

    val e2eeLearnMoreUrl: String
        get() = protocol.e2eeLearnMoreUrl

    fun isTrustedHost(host: String): Boolean {
        val trustedHosts = setOf(
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

    fun isInviteLinkUrl(url: String): Boolean {
        val regex = Regex("^https://[^/]+/u[^?]*\\?([^#&]*&)*pi=[A-Za-z0-9]+.*$")
        return regex.matches(url)
    }
}
