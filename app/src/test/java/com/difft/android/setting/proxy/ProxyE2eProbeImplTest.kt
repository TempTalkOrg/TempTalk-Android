package com.difft.android.setting.proxy

import com.difft.android.base.user.Data
import com.difft.android.base.user.Domain
import com.difft.android.base.user.Host
import com.difft.android.base.user.NewGlobalConfig
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
 * Unit tests for [ProxyE2eProbeImpl] (design §10 T6–T10).
 *
 * Mocks the Retrofit [HttpService] (via a mocked [ChativeHttpClient]) and the
 * [IGlobalConfigsManager]; no real network.
 *
 * [HttpException] has NO `HttpException(Int)` constructor — build a 4xx/404 via
 * `HttpException(Response.error<ResponseBody>(code, "".toResponseBody(null)))` (TEST-2).
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
        probe = ProxyE2eProbeImpl(configsManager, client)
    }

    private fun httpException(code: Int) =
        HttpException(Response.error<ResponseBody>(code, "".toResponseBody(null)))

    private fun configWithSelfHosts(vararg hostNames: String) {
        every { configsManager.getNewGlobalConfigs() } returns NewGlobalConfig(
            data = Data(hosts = hostNames.map { Host(certType = "self", name = it) })
        )
    }

    // T6: 4xx (401) from getResponseBody → probe true (transport/route reached).
    @Test
    fun `4xx response counts as success`() = runTest {
        configWithSelfHosts("chat.test.chative.im")
        coEvery { httpService.getResponseBody(any(), any(), any()) } throws httpException(401)

        assertTrue(probe.probe())
    }

    // T7: SSLHandshakeException (IOException subclass) → probe false.
    @Test
    fun `IOException counts as failure`() = runTest {
        configWithSelfHosts("chat.test.chative.im")
        coEvery { httpService.getResponseBody(any(), any(), any()) } throws
            SSLHandshakeException("handshake failed")

        assertFalse(probe.probe())
    }

    // T8: multi-host fallback — A throws IOException, B throws HttpException(404) → true; both called.
    @Test
    fun `multi host fallback succeeds when later host returns http status`() = runTest {
        configWithSelfHosts("hostA", "hostB")
        coEvery { httpService.getResponseBody("https://hostA/", any(), any()) } throws
            SSLHandshakeException("A down")
        coEvery { httpService.getResponseBody("https://hostB/", any(), any()) } throws
            httpException(404)

        assertTrue(probe.probe())
        coVerify(exactly = 1) { httpService.getResponseBody("https://hostA/", any(), any()) }
        coVerify(exactly = 1) { httpService.getResponseBody("https://hostB/", any(), any()) }
    }

    // T9: only certType=self hosts are probed; authority hosts (srv.*, cloudfront) never requested.
    @Test
    fun `only self cert hosts are probed`() = runTest {
        every { configsManager.getNewGlobalConfigs() } returns NewGlobalConfig(
            data = Data(
                hosts = listOf(
                    Host(certType = "self", name = "chat.temptalk.net"),
                    Host(certType = "authority", name = "srv.temptalk.net"),
                ),
                domains = listOf(
                    Domain(domain = "chat.chative.im", certType = "self"),
                    Domain(domain = "abc.cloudfront.net", certType = "authority"),
                ),
            )
        )
        val urlSlot = mutableListOf<String>()
        coEvery { httpService.getResponseBody(capture(urlSlot), any(), any()) } throws
            httpException(401)

        assertTrue(probe.probe())
        // Stops at first self host (chat.temptalk.net returns 401 = success), never reaches authority.
        assertEquals(listOf("https://chat.temptalk.net/"), urlSlot)
        coVerify(exactly = 0) { httpService.getResponseBody("https://srv.temptalk.net/", any(), any()) }
        coVerify(exactly = 0) { httpService.getResponseBody("https://abc.cloudfront.net/", any(), any()) }
    }

    // T10: no config (null) → false, no request issued.
    @Test
    fun `empty host list returns false without request`() = runTest {
        every { configsManager.getNewGlobalConfigs() } returns null

        assertFalse(probe.probe())
        coVerify(exactly = 0) { httpService.getResponseBody(any(), any(), any()) }
    }
}
