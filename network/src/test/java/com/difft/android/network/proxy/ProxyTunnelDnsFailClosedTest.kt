package com.difft.android.network.proxy

import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.UnknownHostException





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
