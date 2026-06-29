package com.difft.android.setting.proxy

import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ProxyE2eProbeImpl].
 *
 * Mocks the Retrofit [HttpService] (via a mocked [ChativeHttpClient]) and the
 * [IGlobalConfigsManager]; no real network.
 *
 * Probe hosts come from the proxy chat tunnel domains
 * ([IGlobalConfigsManager.getProxyTunnelChatDomains]) — the same hosts the proxy
 * path actually uses; that getter already does live-preferred + embedded fallback.
 *
 * [HttpException] has NO `HttpException(Int)` constructor — build a 4xx/404 via
 * `HttpException(Response.error<ResponseBody>(code, "".toResponseBody(null)))`.
 */
class ProxyE2eProbeImplTest {

    private lateinit var configsManager: IGlobalConfigsManager
    private lateinit var client: ChativeHttpClient
    private lateinit var httpService: HttpService
    private lateinit var probe: ProxyE2eProbeImpl

    @Before
    fun setup() {
        configsManager = mockk()
        client = mockk()
        httpService = mockk()
        every { client.httpService } returns httpService
        // Default: no probe hosts. Tests that exercise the probe path override
        // getProxyTunnelChatDomains() via [embeddedHosts].
        every { configsManager.getProxyTunnelChatDomains() } returns emptyList()
        probe = ProxyE2eProbeImpl(configsManager, client)
    }

    private fun httpException(code: Int) =
        HttpException(Response.error<ResponseBody>(code, "".toResponseBody(null)))

    private fun embeddedHosts(vararg hostNames: String) {
        every { configsManager.getProxyTunnelChatDomains() } returns hostNames.toList()
    }

    // 4xx (401) from getResponseBody → probe true (transport/route reached).
    @Test
    fun `4xx response counts as success`() = runTest {
        embeddedHosts("chat.test.chative.im")
        coEvery { httpService.getResponseBody(any(), any(), any()) } throws httpException(401)

        assertTrue(probe.probe())
    }

    // SSLHandshakeException (IOException subclass) → probe false.
    @Test
    fun `IOException counts as failure`() = runTest {
        embeddedHosts("chat.test.chative.im")
        coEvery { httpService.getResponseBody(any(), any(), any()) } throws
            SSLHandshakeException("handshake failed")

        assertFalse(probe.probe())
    }

    // Multi-host fallback — A throws IOException, B throws HttpException(404) → true; both called.
    @Test
    fun `multi host fallback succeeds when later host returns http status`() = runTest {
        embeddedHosts("hostA", "hostB")
        coEvery { httpService.getResponseBody("https://hostA/", any(), any()) } throws
            SSLHandshakeException("A down")
        coEvery { httpService.getResponseBody("https://hostB/", any(), any()) } throws
            httpException(404)

        assertTrue(probe.probe())
        coVerify(exactly = 1) { httpService.getResponseBody("https://hostA/", any(), any()) }
        coVerify(exactly = 1) { httpService.getResponseBody("https://hostB/", any(), any()) }
    }

    // Proxy chat tunnel domains are probed in order (stops at first success).
    @Test
    fun `proxy chat domains are probed in order`() = runTest {
        embeddedHosts("chat.temptalk.net", "chat.chative.im")
        val urlSlot = mutableListOf<String>()
        coEvery { httpService.getResponseBody(capture(urlSlot), any(), any()) } throws
            httpException(401)

        assertTrue(probe.probe())
        // Stops at first host (chat.temptalk.net returns 401 = success).
        assertEquals(listOf("https://chat.temptalk.net/"), urlSlot)
    }

    // No proxy chat domains → false, no request issued. (The getter already does
    // live-preferred + embedded fallback internally, so an empty result here means
    // neither source carried the block.)
    @Test
    fun `empty host list returns false without request`() = runTest {
        every { configsManager.getProxyTunnelChatDomains() } returns emptyList()

        assertFalse(probe.probe())
        coVerify(exactly = 0) { httpService.getResponseBody(any(), any(), any()) }
    }
}
