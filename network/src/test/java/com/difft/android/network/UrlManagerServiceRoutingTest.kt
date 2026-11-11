package com.difft.android.network

import com.difft.android.base.user.Data
import com.difft.android.base.user.Domain
import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.base.user.Service
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals





class UrlManagerServiceRoutingTest {

    private val environmentHelper = mockk<EnvironmentHelper>(relaxed = true)
    private val globalConfigsManager = mockk<GlobalConfigsManager>(relaxed = true)
    private val coordinator = mockk<DomainSpeedTestCoordinator>(relaxed = true)
    private val proxyProvider = mockk<ProxyConfigProvider>(relaxed = true)

    private lateinit var urlManager: UrlManager

    private val prodDomains = listOf(
        Domain(label = "chat1", domain = "chat.chative.im", certType = "self"),
        Domain(label = "chat2", domain = "chat.chative.online", certType = "self"),
        Domain(label = "chat3", domain = "chat.chative.ninja", certType = "self"),
        Domain(label = "chat4", domain = "chat.temptalk.net", certType = "self"),
    )

    private val prodServices = listOf(
        Service(name = "chat", path = "/chat", domains = listOf("chat1", "chat2", "chat3", "chat4")),
        Service(name = "call", path = "/call", domains = listOf("chat1", "chat2", "chat3", "chat4")),
        Service(name = "fileSharing", path = "/fileshare", domains = listOf("chat1", "chat2", "chat3", "chat4")),
    )

    private fun config(data: Data?): NewGlobalConfig = NewGlobalConfig(code = 0, data = data)

    @Before
    fun setUp() {
        every { environmentHelper.ENVIRONMENT_ONLINE } returns "online"
        every { environmentHelper.ENVIRONMENT_DEVELOPMENT } returns "dev"
        every { environmentHelper.isThatEnvironment("online") } returns true
        every { environmentHelper.isThatEnvironment("dev") } returns false

        
        every { proxyProvider.isEnabled } returns false
        every { coordinator.getBestHostSync() } returns "best.host"

        urlManager = UrlManager(
            environmentHelper = environmentHelper,
            globalConfigsManager = dagger.Lazy { globalConfigsManager },
            coordinator = dagger.Lazy { coordinator },
            proxyConfigProvider = dagger.Lazy { proxyProvider },
        )
    }

    

    @Test
    fun `chat call fileSharing default use resolver paths when services present`() {
        every { globalConfigsManager.getNewGlobalConfigs() } returns
            config(Data(domains = prodDomains, services = prodServices))

        assertEquals("https://best.host/chat/", urlManager.chat)
        assertEquals("https://best.host/call/", urlManager.call)
        assertEquals("https://best.host/fileshare/", urlManager.fileSharing)
        assertEquals("https://best.host/", urlManager.default)
    }

    @Test
    fun `chat websocket url uses resolver path when services present`() {
        every { globalConfigsManager.getNewGlobalConfigs() } returns
            config(Data(domains = prodDomains, services = prodServices))

        assertEquals("wss://best.host/chat/v1/websocket/", urlManager.getChatWebsocketUrl())
    }

    

    @Test
    fun `paths fall back to end defaults when services absent`() {
        every { globalConfigsManager.getNewGlobalConfigs() } returns null

        assertEquals("https://best.host/chat/", urlManager.chat)
        assertEquals("https://best.host/call/", urlManager.call)
        assertEquals("https://best.host/fileshare/", urlManager.fileSharing)
    }

    @Test
    fun `host falls back to defaultHost when coordinator and services absent`() {
        every { globalConfigsManager.getNewGlobalConfigs() } returns null
        every { coordinator.getBestHostSync() } returns null

        
        assertEquals("https://chat.chative.im/chat/", urlManager.chat)
        assertEquals("https://chat.chative.im/", urlManager.default)
        assertEquals("wss://chat.chative.im/chat/v1/websocket/", urlManager.getChatWebsocketUrl())
    }

    @Test
    fun `websocket url falls back to end default path when services absent`() {
        every { globalConfigsManager.getNewGlobalConfigs() } returns null

        assertEquals("wss://best.host/chat/v1/websocket/", urlManager.getChatWebsocketUrl())
    }

    

    @Test
    fun `servicePathFor returns the live resolver path per service`() {
        every { globalConfigsManager.getNewGlobalConfigs() } returns
            config(Data(domains = prodDomains, services = prodServices))

        assertEquals("/chat", urlManager.servicePathFor(ChatService.CHAT))
        assertEquals("/call", urlManager.servicePathFor(ChatService.CALL))
        assertEquals("/fileshare", urlManager.servicePathFor(ChatService.FILE_SHARING))
    }

    @Test
    fun `servicePathFor falls back to the end default and is empty for the default client`() {
        every { globalConfigsManager.getNewGlobalConfigs() } returns null

        assertEquals("/chat", urlManager.servicePathFor(ChatService.CHAT))
        assertEquals("", urlManager.servicePathFor(ChatService.DEFAULT))
    }

    @Test
    fun `servicePathFor drops a trailing slash and follows a live config refresh`() {
        every { globalConfigsManager.getNewGlobalConfigs() } returns config(
            Data(domains = prodDomains, services = listOf(Service(name = "chat", path = "/chatv2/", domains = listOf("chat1"))))
        )

        assertEquals("/chatv2", urlManager.servicePathFor(ChatService.CHAT))
    }

    @Test
    fun `currentHostFor is the coordinator best host regardless of service`() {
        every { globalConfigsManager.getNewGlobalConfigs() } returns null
        every { coordinator.getBestHostSync() } returns "ranked.host"

        ChatService.entries.forEach { assertEquals("ranked.host", urlManager.currentHostFor(it)) }
    }

    

    @Test
    fun `path follows a live config refresh between getter calls`() {
        
        every { globalConfigsManager.getNewGlobalConfigs() } returns
            config(Data(domains = prodDomains, services = prodServices))
        assertEquals("https://best.host/chat/", urlManager.chat)

        
        every { globalConfigsManager.getNewGlobalConfigs() } returns
            config(
                Data(
                    domains = prodDomains,
                    services = listOf(
                        Service(name = "chat", path = "/chatv2", domains = listOf("chat1")),
                    ),
                )
            )
        assertEquals("https://best.host/chatv2/", urlManager.chat)
    }
}
