package com.difft.android.network.proxy

import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.IConnectionRefresher
import com.difft.android.base.utils.IGlobalConfigsManager
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

/**
 * [ProxyConfigProvider] must evict the HTTP connection pools (via
 * [ProxyHttpConnectionRegistry]) on a REAL proxy state change — the HTTP
 * counterpart of the IM-WebSocket reconnect — and NOT on no-op writes.
 *
 * Without this, OkHttp keep-alive would reuse the stale tunnel/direct socket and
 * requests would keep flowing through the old route after the toggle.
 */
@RunWith(RobolectricTestRunner::class)
class ProxyConfigProviderHttpEvictionTest {

    private class FakeUserManager : UserManager {
        private var current: UserData? = UserData()
        override fun setUserData(userData: UserData, commit: Boolean) { current = userData }
        override fun getUserData(): UserData? = current
    }

    private val validLink: String = ProxyConfig(
        host = "1.2.3.4",
        port = 443,
        spkiPinBase64 = "pin",
    ).toShareLink()

    private fun makeProvider(): Pair<ProxyConfigProvider, ProxyHttpConnectionRegistry> {
        val gcm = mockk<IGlobalConfigsManager>(relaxed = true)
        every { gcm.getNewGlobalConfigs() } returns null
        val registry = spyk(ProxyHttpConnectionRegistry())
        val provider = ProxyConfigProvider(
            userManager = FakeUserManager(),
            globalConfigsManagerLazy = Lazy { gcm },
            connectionRefresherLazy = Lazy { object : IConnectionRefresher {
                override fun reconnectAfterProxyChange() = Unit
            } },
            httpConnectionRegistry = registry,
        )
        return provider to registry
    }

    @Test
    fun `enabling proxy evicts http pools`() {
        val (provider, registry) = makeProvider()
        assertTrue(provider.save(validLink, enabled = true))
        verify(exactly = 1) { registry.evictAll() }
    }

    @Test
    fun `disabling proxy evicts http pools`() {
        val (provider, registry) = makeProvider()
        assertTrue(provider.save(validLink, enabled = true)) // off -> on (1)
        provider.setEnabled(false)                           // on -> off (2)
        verify(exactly = 2) { registry.evictAll() }
    }

    @Test
    fun `clear after active state evicts http pools`() {
        val (provider, registry) = makeProvider()
        assertTrue(provider.save(validLink, enabled = true)) // (1)
        provider.clear()                                     // (2)
        verify(exactly = 2) { registry.evictAll() }
    }

    @Test
    fun `no-op save does not evict http pools`() {
        val (provider, registry) = makeProvider()
        assertTrue(provider.save(validLink, enabled = true)) // (1)
        assertTrue(provider.save(validLink, enabled = true)) // no change -> no evict
        verify(exactly = 1) { registry.evictAll() }
    }

    @Test
    fun `clear on empty state does not evict http pools`() {
        val (provider, registry) = makeProvider()
        provider.clear()
        verify(exactly = 0) { registry.evictAll() }
    }
}
