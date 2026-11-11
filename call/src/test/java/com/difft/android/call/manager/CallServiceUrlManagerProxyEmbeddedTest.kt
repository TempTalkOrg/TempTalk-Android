package com.difft.android.call.manager

import com.difft.android.base.storage.SecureConfigStore
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.proxy.ProxyConfigProvider
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull









@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CallServiceUrlManagerProxyEmbeddedTest {

    private fun newManager(
        proxyEnabled: Boolean,
        proxyCallDomains: List<String> = listOf("test-primary.ablivekit.org", "test-fallback.ablivekit.org"),
    ): CallServiceUrlManager {
        val secureConfigStore = mockk<SecureConfigStore>(relaxed = true)
        every { secureConfigStore.callServiceUrlStateV3Flow } returns flowOf("")
        val statsLog = mockk<CallStatisticsLogManager>(relaxed = true)
        val httpClient = mockk<ChativeHttpClient>(relaxed = true)
        val proxyProvider = mockk<ProxyConfigProvider>(relaxed = true)
        
        
        every { proxyProvider.isEnabledForCall } returns proxyEnabled
        val gcm = mockk<IGlobalConfigsManager>(relaxed = true)
        every { gcm.getProxyTunnelCallDomains() } returns proxyCallDomains
        val physicalTransport = mockk<CallPhysicalHttpTransport>(relaxed = true)

        return CallServiceUrlManager(
            statisticsLogManager = Lazy { statsLog },
            callHttpClient = Lazy { httpClient },
            secureConfigStore = secureConfigStore,
            proxyConfigProviderLazy = Lazy { proxyProvider },
            globalConfigsManagerLazy = Lazy { gcm },
            physicalHttpTransport = Lazy { physicalTransport },
        )
    }

    @Test
    fun `ensureServiceUrlsForCall synthesizes serviceUrls from proxy call domains`() = runBlocking {
        val manager = newManager(proxyEnabled = true)

        val result = manager.ensureServiceUrlsForCall()

        assertNotNull(result)
        assertEquals("test-primary.ablivekit.org", result.primary?.domain)
        assertEquals(1, result.fallback.size)
        assertEquals("test-fallback.ablivekit.org", result.fallback.first()?.domain)
        
        assertEquals(emptyList(), result.primary?.addrs)
    }

    @Test
    fun `getCachedServiceUrls synthesizes serviceUrls from proxy call domains`() {
        val manager = newManager(proxyEnabled = true)

        val result = manager.getCachedServiceUrls()

        assertNotNull(result)
        assertEquals("test-primary.ablivekit.org", result.primary?.domain)
    }

    @Test
    fun `ensureServiceUrlsForCall returns null when proxy enabled but no call domains`() = runBlocking {
        val manager = newManager(proxyEnabled = true, proxyCallDomains = emptyList())

        assertNull(manager.ensureServiceUrlsForCall())
    }
}
