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
        every { configsManager.getProxyTunnelChatDomains() } returns emptyList()
        probe = ProxyE2eProbeImpl(configsManager, client)
    }

    private fun httpException(code: Int) =
        HttpException(Response.error<ResponseBody>(code, "".toResponseBody(null)))

    private fun embeddedHosts(vararg hostNames: String) {
        every { configsManager.getProxyTunnelChatDomains() } returns hostNames.toList()
    }

    
    @Test
    fun `4xx response counts as success`() = runTest {
        embeddedHosts("chat.test.chative.im")
        coEvery { httpService.getResponseBody(any(), any(), any()) } throws httpException(401)

        assertTrue(probe.probe())
    }

    @Test
    fun `IOException counts as failure`() = runTest {
        embeddedHosts("chat.test.chative.im")
        coEvery { httpService.getResponseBody(any(), any(), any()) } throws
            SSLHandshakeException("handshake failed")

        assertFalse(probe.probe())
    }

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

    @Test
    fun `proxy chat domains are probed in order`() = runTest {
        embeddedHosts("chat.temptalk.net", "chat.chative.im")
        val urlSlot = mutableListOf<String>()
        coEvery { httpService.getResponseBody(capture(urlSlot), any(), any()) } throws
            httpException(401)

        assertTrue(probe.probe())
        
        assertEquals(listOf("https://chat.temptalk.net/"), urlSlot)
    }

    
    @Test
    fun `empty host list returns false without request`() = runTest {
        every { configsManager.getProxyTunnelChatDomains() } returns emptyList()

        assertFalse(probe.probe())
        coVerify(exactly = 0) { httpService.getResponseBody(any(), any(), any()) }
    }
}
