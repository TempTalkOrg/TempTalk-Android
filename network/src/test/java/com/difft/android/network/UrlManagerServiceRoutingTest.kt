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

/**
 * Non-proxy service routing on [UrlManager]. Paths now come from the `services`
 * + `domains` model via [ServiceUrlResolver]; when services are absent the path
 * falls back to the end default. Host selection is mocked at the coordinator, so
 * these tests isolate the path-resolution + URL-shape logic.
 *
 * Also asserts the path follows a live config refresh (the old `by lazy` freeze
 * is gone): swapping the mocked config between getter calls must change the
 * emitted path.
 */
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

        // Non-proxy path; pin a deterministic host so we assert on the PATH shape.
        every { proxyProvider.isEnabled } returns false
        every { coordinator.getBestHostSync() } returns "best.host"

        urlManager = UrlManager(
            environmentHelper = environmentHelper,
            globalConfigsManager = dagger.Lazy { globalConfigsManager },
            coordinator = dagger.Lazy { coordinator },
            proxyConfigProvider = dagger.Lazy { proxyProvider },
        )
    }

    // -- services present: path comes from the resolver --

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

    // -- services absent: path falls back to end default; host falls back to defaultHost --

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

        // Online protocol defaultHost is chat.chative.im.
        assertEquals("https://chat.chative.im/chat/", urlManager.chat)
        assertEquals("https://chat.chative.im/", urlManager.default)
        assertEquals("wss://chat.chative.im/chat/v1/websocket/", urlManager.getChatWebsocketUrl())
    }

    @Test
    fun `websocket url falls back to end default path when services absent`() {
        every { globalConfigsManager.getNewGlobalConfigs() } returns null

        assertEquals("wss://best.host/chat/v1/websocket/", urlManager.getChatWebsocketUrl())
    }

    // -- live refresh: path follows config swap (by lazy freeze removed) --

    @Test
    fun `path follows a live config refresh between getter calls`() {
        // First read: services present → resolver path "/chat".
        every { globalConfigsManager.getNewGlobalConfigs() } returns
            config(Data(domains = prodDomains, services = prodServices))
        assertEquals("https://best.host/chat/", urlManager.chat)

        // Server pushes a config with a different chat path → next read must follow.
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
