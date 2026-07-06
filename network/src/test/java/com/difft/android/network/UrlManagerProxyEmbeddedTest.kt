package com.difft.android.network

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
 * Proxy-active forcing on [UrlManager]: while the proxy is enabled, the request
 * HOST must come from the embedded assets config (deterministic, whitelist-aligned),
 * with WS/HTTP failover preserved via [DomainSpeedTestCoordinator.firstAvailableHost].
 * Service PATHS always come from the live GlobalConfig (path is not part of the
 * host-based tunnel decision). When disabled, the original speed-test / live-config
 * host selection is restored.
 */
class UrlManagerProxyEmbeddedTest {

    // Primary host matches the production single-host embedded config
    // (default_global_config.json); the second is a synthetic placeholder kept
    // only to exercise the multi-host failover path.
    private val embeddedHosts = listOf("chat.chative.im", "chat.failover.example")

    private val environmentHelper = mockk<EnvironmentHelper>(relaxed = true)
    private val globalConfigsManager = mockk<GlobalConfigsManager>(relaxed = true)
    private val coordinator = mockk<DomainSpeedTestCoordinator>(relaxed = true)
    private val proxyProvider = mockk<ProxyConfigProvider>(relaxed = true)

    private lateinit var urlManager: UrlManager

    @Before
    fun setUp() {
        every { environmentHelper.ENVIRONMENT_ONLINE } returns "online"
        every { environmentHelper.ENVIRONMENT_DEVELOPMENT } returns "dev"
        every { environmentHelper.isThatEnvironment("online") } returns true
        every { environmentHelper.isThatEnvironment("dev") } returns false

        every { globalConfigsManager.getProxyTunnelChatDomains() } returns embeddedHosts
        // Live config absent → service paths fall back to the hardcoded defaults
        // ("/chat", "/call", "/fileshare") regardless of proxy state.
        every { globalConfigsManager.getNewGlobalConfigs() } returns null

        urlManager = UrlManager(
            environmentHelper = environmentHelper,
            globalConfigsManager = dagger.Lazy { globalConfigsManager },
            coordinator = dagger.Lazy { coordinator },
            proxyConfigProvider = dagger.Lazy { proxyProvider },
        )
    }

    @Test
    fun `chat url uses embedded host and path when proxy enabled`() {
        every { proxyProvider.isEnabled } returns true
        every { coordinator.firstAvailableHost(embeddedHosts) } returns "chat.chative.im"

        assertEquals("https://chat.chative.im/chat/", urlManager.chat)
    }

    @Test
    fun `chat url fails over to next embedded host when first is unavailable`() {
        every { proxyProvider.isEnabled } returns true
        // Simulates first embedded host marked unavailable → coordinator returns the next.
        every { coordinator.firstAvailableHost(embeddedHosts) } returns "chat.failover.example"

        assertEquals("https://chat.failover.example/chat/", urlManager.chat)
    }

    @Test
    fun `getAllHostsRanked returns embedded hosts when proxy enabled`() {
        every { proxyProvider.isEnabled } returns true

        assertEquals(embeddedHosts, urlManager.getAllHostsRanked())
    }

    @Test
    fun `chat url restores live selection when proxy disabled`() {
        every { proxyProvider.isEnabled } returns false
        every { coordinator.getBestHostSync() } returns "live.host"

        assertEquals("https://live.host/chat/", urlManager.chat)
    }

    @Test
    fun `getAllHostsRanked delegates to coordinator when proxy disabled`() {
        every { proxyProvider.isEnabled } returns false
        every { coordinator.getAllHostsRanked() } returns listOf("live.host")

        assertEquals(listOf("live.host"), urlManager.getAllHostsRanked())
    }

    @Test
    fun `proxyForcedHostOrNull returns embedded host when proxy enabled`() {
        every { proxyProvider.isEnabled } returns true
        every { coordinator.firstAvailableHost(embeddedHosts) } returns "chat.chative.im"

        assertEquals("chat.chative.im", urlManager.proxyForcedHostOrNull())
    }

    @Test
    fun `proxyForcedHostOrNull returns null when proxy disabled`() {
        every { proxyProvider.isEnabled } returns false

        assertEquals(null, urlManager.proxyForcedHostOrNull())
    }
}
