package com.difft.android.network

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebsocketUrlManager @Inject constructor(
    private val websocketHostManager: WebsocketHostManager
) {
    private val newGlobalConfig by lazy {
        websocketHostManager.newGlobalConfig
    }

    fun getChatWebsocketUrl(): String {
        val chatHost = websocketHostManager.getHostForChatWebsocket()
        val path = newGlobalConfig?.data?.srvs?.chat ?: ""
        return "wss://$chatHost$path/v1/websocket/"
    }

    fun getChatWebsocketHostCounts(): Int {
        return websocketHostManager.allHostsForChatWebsocket.size
    }

    fun switchToNextChatWebsocketHost() {
        websocketHostManager.switchToNextHostForChatWebsocket()
    }

    fun recordCurrentChatHostAsSuccess() {
        websocketHostManager.recordSuccessConnectedChatWebsocketHost(websocketHostManager.getHostForChatWebsocket())
    }
}