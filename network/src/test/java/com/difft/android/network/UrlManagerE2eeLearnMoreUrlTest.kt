package com.difft.android.network

import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals

/**
 * T1-4/T1-5: [UrlManager.e2eeLearnMoreUrl] resolves per-environment, mirroring the existing
 * [UrlManager.installationGuideUrl] property.
 * Both online and dev protocols intentionally return the same URL — this is
 * pinned here so a future edit to only one protocol's value is caught as a regression.
 */
class UrlManagerE2eeLearnMoreUrlTest {

    private val environmentHelper = mockk<EnvironmentHelper>(relaxed = true)
    private val globalConfigsManager = mockk<GlobalConfigsManager>(relaxed = true)
    private val coordinator = mockk<DomainSpeedTestCoordinator>(relaxed = true)
    private val proxyProvider = mockk<ProxyConfigProvider>(relaxed = true)

    private fun urlManager(online: Boolean): UrlManager {
        every { environmentHelper.ENVIRONMENT_ONLINE } returns "online"
        every { environmentHelper.ENVIRONMENT_DEVELOPMENT } returns "dev"
        every { environmentHelper.isThatEnvironment("online") } returns online
        every { environmentHelper.isThatEnvironment("dev") } returns !online
        return UrlManager(
            environmentHelper = environmentHelper,
            globalConfigsManager = dagger.Lazy { globalConfigsManager },
            coordinator = dagger.Lazy { coordinator },
            proxyConfigProvider = dagger.Lazy { proxyProvider },
        )
    }

    @Test
    fun `T1-4 e2eeLearnMoreUrl returns online-protocol placeholder when online env active`() {
        assertEquals("https://quicall.app/security", urlManager(online = true).e2eeLearnMoreUrl)
    }

    @Test
    fun `T1-5 e2eeLearnMoreUrl returns dev-protocol placeholder when dev env active (same value by design)`() {
        assertEquals("https://quicall.app/security", urlManager(online = false).e2eeLearnMoreUrl)
    }
}
