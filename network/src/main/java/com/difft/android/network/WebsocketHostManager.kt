package com.difft.android.network

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.network.config.GlobalConfigsManager
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebsocketHostManager @Inject constructor(
    private val globalConfigsManager: Lazy<GlobalConfigsManager>,
    private val userManager: UserManager,
    environmentHelper: EnvironmentHelper,
) {
    private class Hosts(
        val chatHost: String
    )

    val newGlobalConfig by lazy {
        globalConfigsManager.get().getNewGlobalConfigs()
    }

    val allHostsForChatWebsocket by lazy {
        newGlobalConfig?.data?.hosts?.filter { it.servTo == "chat" }?.mapNotNull { it.name.takeIf { it.isNullOrBlank().not() } }.orEmpty()
    }

    @Volatile
    private var currentHostIndexForChatWebsocket = -1

    private val defaultHosts: Hosts by lazy {
        when {
            environmentHelper.isThatEnvironment(environmentHelper.ENVIRONMENT_ONLINE) -> Hosts("chat.chative.im")
            environmentHelper.isThatEnvironment(environmentHelper.ENVIRONMENT_DEVELOPMENT) -> Hosts("chat.test.chative.im")
            else -> throw IllegalArgumentException("Unknown Environment.")
        }
    }

    private fun previousSuccessConnectedChatWebsocketHost(): String? {
        return userManager.getUserData()?.previousSuccessConnectedChatWebsocketHost
    }

    fun getHostForChatWebsocket(): String {
        val previousSuccessConnectedHost = previousSuccessConnectedChatWebsocketHost()
        if (!previousSuccessConnectedHost.isNullOrBlank()) {
            currentHostIndexForChatWebsocket = allHostsForChatWebsocket.indexOf(previousSuccessConnectedHost)
            return previousSuccessConnectedHost
        }
        return if (allHostsForChatWebsocket.isEmpty()) {
            defaultHosts.chatHost
        } else {
            allHostsForChatWebsocket[currentHostIndexForChatWebsocket.takeIf { it != -1 } ?: 0]
        }
    }

    @Synchronized
    fun switchToNextHostForChatWebsocket() {
        if (currentHostIndexForChatWebsocket == -1) {
            val previousSuccessConnectedHost = previousSuccessConnectedChatWebsocketHost()
            if (!previousSuccessConnectedHost.isNullOrBlank()) {
                currentHostIndexForChatWebsocket = allHostsForChatWebsocket.indexOf(previousSuccessConnectedHost)
            }
        }
        if (allHostsForChatWebsocket.isNotEmpty()) {
            currentHostIndexForChatWebsocket = (currentHostIndexForChatWebsocket + 1) % allHostsForChatWebsocket.size
        } else {
            L.i { "No hosts for chat websocket, switch host failed" }
        }
        recordSuccessConnectedChatWebsocketHost("")
    }

    fun recordSuccessConnectedChatWebsocketHost(host: String) {
        userManager.update {
            this.previousSuccessConnectedChatWebsocketHost = host
        }
    }
}