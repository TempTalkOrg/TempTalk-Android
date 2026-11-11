package com.difft.android.network.proxy

import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.IConnectionRefresher
import com.difft.android.base.utils.IGlobalConfigsManager
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue













@RunWith(RobolectricTestRunner::class)
class ProxyConfigProviderTunnelHostsTest {

    
    private class FakeUserManager : UserManager {
        private var current: UserData? = UserData()
        override fun setUserData(userData: UserData, commit: Boolean) {
            current = userData
        }

        override fun getUserData(): UserData? = current
    }

    private object NoOpRefresher : IConnectionRefresher {
        override fun reconnectAfterProxyChange() = Unit
    }

    private val noOpRefresherLazy: Lazy<IConnectionRefresher> = Lazy { NoOpRefresher }

    private fun makeProvider(
        chatDomains: List<String>,
        callDomains: List<String>,
    ): Pair<ProxyConfigProvider, IGlobalConfigsManager> {
        val gcm = mockk<IGlobalConfigsManager>(relaxed = true)
        every { gcm.getProxyTunnelChatDomains() } returns chatDomains
        every { gcm.getProxyTunnelCallDomains() } returns callDomains
        val provider = ProxyConfigProvider(
            userManager = FakeUserManager(),
            globalConfigsManagerLazy = Lazy { gcm },
            connectionRefresherLazy = noOpRefresherLazy,
        )
        return provider to gcm
    }

    @Test
    fun `shouldTunnel - exact match against an embedded chat host`() {
        val (provider, _) = makeProvider(
            chatDomains = listOf("chat.temptalk.net"),
            callDomains = emptyList(),
        )

        assertTrue(provider.shouldTunnel("chat.temptalk.net"))
    }

    @Test
    fun `shouldTunnel - subdomain match against an embedded chat host`() {
        val (provider, _) = makeProvider(
            chatDomains = listOf("temptalk.net"),
            callDomains = emptyList(),
        )

        assertTrue(provider.shouldTunnel("chat.temptalk.net"))
    }

    @Test
    fun `shouldTunnel - matches an embedded call-service domain`() {
        
        
        val (provider, _) = makeProvider(
            chatDomains = listOf("chat.temptalk.net"),
            callDomains = listOf("pr7.ablivekit.org"),
        )

        assertTrue(provider.shouldTunnel("pr7.ablivekit.org"))
        assertTrue(provider.shouldTunnel("media.pr7.ablivekit.org"))
    }

    @Test
    fun `shouldTunnel - sibling FQDN does not match unrelated parent`() {
        val (provider, _) = makeProvider(
            chatDomains = listOf("srv.example.org"),
            callDomains = emptyList(),
        )

        assertFalse(provider.shouldTunnel("api.example.org"))
        assertTrue(provider.shouldTunnel("srv.example.org"))
        assertTrue(provider.shouldTunnel("child.srv.example.org"))
    }

    @Test
    fun `shouldTunnel - query side normalization upper case and trailing dot`() {
        val (provider, _) = makeProvider(
            chatDomains = listOf("chat.chative.im"),
            callDomains = emptyList(),
        )

        assertTrue(provider.shouldTunnel("API.CHAT.CHATIVE.IM."))
    }

    @Test
    fun `shouldTunnel - non-match returns false`() {
        val (provider, _) = makeProvider(
            chatDomains = listOf("chat.chative.im"),
            callDomains = emptyList(),
        )

        assertFalse(provider.shouldTunnel("example.org"))
    }

    @Test
    fun `shouldTunnel - both sources empty fails closed and tunnels everything`() {
        
        
        
        val (provider, _) = makeProvider(
            chatDomains = emptyList(),
            callDomains = emptyList(),
        )

        assertTrue(provider.shouldTunnel("chat.temptalk.net"))
        assertTrue(provider.shouldTunnel("anything.example"))
    }

    @Test
    fun `shouldTunnel - empty chat hosts with call domains still fails closed`() {
        
        
        
        
        val (provider, _) = makeProvider(
            chatDomains = emptyList(),
            callDomains = listOf("pr7.ablivekit.org"),
        )

        
        assertTrue(provider.shouldTunnel("chat.temptalk.net"))
        assertTrue(provider.shouldTunnel("anything.example"))
    }

    @Test
    fun `empty whitelist is not cached and re-derives on next call`() {
        
        
        val (provider, gcm) = makeProvider(
            chatDomains = emptyList(),
            callDomains = emptyList(),
        )

        provider.shouldTunnel("chat.temptalk.net")
        provider.shouldTunnel("chat.temptalk.net")

        
        verify(atLeast = 2) { gcm.getProxyTunnelChatDomains() }
    }

    @Test
    fun `non-empty whitelist union is cached and reused across many shouldTunnel calls`() {
        val (provider, gcm) = makeProvider(
            chatDomains = listOf("chat.temptalk.net"),
            callDomains = emptyList(),
        )

        repeat(50) { assertTrue(provider.shouldTunnel("chat.temptalk.net")) }

        
        
        
        verify(atLeast = 1) { gcm.getProxyTunnelChatDomains() }
        verify(atLeast = 1) { gcm.getProxyTunnelCallDomains() }
    }

    @Test
    fun `whitelist re-derives when source domain list reference changes`() {
        val gcm = mockk<IGlobalConfigsManager>(relaxed = true)
        
        every { gcm.getProxyTunnelChatDomains() } returns listOf("chat.temptalk.net")
        every { gcm.getProxyTunnelCallDomains() } returns emptyList()
        val provider = ProxyConfigProvider(
            userManager = FakeUserManager(),
            globalConfigsManagerLazy = Lazy { gcm },
            connectionRefresherLazy = noOpRefresherLazy,
        )
        assertTrue(provider.shouldTunnel("chat.temptalk.net"))
        assertFalse(provider.shouldTunnel("chat.chative.im"))

        
        every { gcm.getProxyTunnelChatDomains() } returns listOf("chat.chative.im")
        assertTrue(provider.shouldTunnel("chat.chative.im"))
        assertFalse(provider.shouldTunnel("chat.temptalk.net"))
    }

    @Test
    fun `clear preserves tunnel-host snapshot (set independent of proxy on-off)`() {
        val (provider, _) = makeProvider(
            chatDomains = listOf("chat.temptalk.net"),
            callDomains = listOf("pr7.ablivekit.org"),
        )
        assertTrue(provider.shouldTunnel("chat.temptalk.net"))
        assertTrue(provider.shouldTunnel("pr7.ablivekit.org"))

        provider.clear()

        
        
        assertTrue(provider.shouldTunnel("chat.temptalk.net"))
        assertTrue(provider.shouldTunnel("pr7.ablivekit.org"))
    }

    @Test
    fun `concurrent shouldTunnel - no torn read during lazy derivation`() {
        val (provider, _) = makeProvider(
            chatDomains = listOf("chat.temptalk.net"),
            callDomains = listOf("pr7.ablivekit.org"),
        )

        val iterations = 1000
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(4)
        val errors = mutableListOf<Throwable>()
        val errorsLock = Any()

        val workers = (1..4).map { idx ->
            Thread({
                try {
                    startGate.await()
                    repeat(iterations) {
                        assertTrue(provider.shouldTunnel("chat.temptalk.net"))
                        assertTrue(provider.shouldTunnel("pr7.ablivekit.org"))
                        assertFalse(provider.shouldTunnel("nope.example"))
                    }
                } catch (t: Throwable) {
                    synchronized(errorsLock) { errors.add(t) }
                } finally {
                    doneGate.countDown()
                }
            }, "tunnel-worker-$idx")
        }
        workers.forEach { it.start() }

        startGate.countDown()
        val finished = doneGate.await(30, TimeUnit.SECONDS)

        assertTrue(finished, "workers did not finish within 30s")
        assertTrue(
            errors.isEmpty(),
            "concurrent shouldTunnel raised: ${errors.joinToString { it.message ?: it.toString() }}",
        )
    }

    @Test
    fun `provider constructs cleanly and derives whitelist on first use`() {
        val (provider, _) = makeProvider(
            chatDomains = listOf("chat.temptalk.net"),
            callDomains = emptyList(),
        )

        assertNotNull(provider)
        assertTrue(provider.shouldTunnel("chat.temptalk.net"))
    }
}
