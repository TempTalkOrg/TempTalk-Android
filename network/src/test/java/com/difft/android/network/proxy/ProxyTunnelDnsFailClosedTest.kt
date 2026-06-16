package com.difft.android.network.proxy

import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.UnknownHostException

/**
 * C-1: DNS leak fail-closed.
 *
 * When the proxy is enabled and the origin host is on the tunnel whitelist,
 * [ProxyTunnelDns.lookup] MUST NOT fall back to system DNS on the original
 * hostname when proxy-host resolution fails — that would leak the tunneled
 * hostname into the system resolver and defeat the privacy invariant.
 */
@RunWith(RobolectricTestRunner::class)
class ProxyTunnelDnsFailClosedTest {

    @Test(expected = UnknownHostException::class)
    fun `tunneled host falls closed (throws UnknownHostException) when proxy host resolution fails`() {
        val provider = mockk<ProxyConfigProvider>()
        every { provider.current } returns ProxyConfig(
            host = "definitely.nonexistent.invalid.tld.999",
            port = 443,
            spkiPinBase64 = "pin",
        )
        every { provider.shouldTunnel(any()) } returns true

        ProxyTunnelDns(provider).lookup("chat.chative.im")
    }
}
