package com.difft.android.network

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * Host-pinning behavior of [ProxyHostInterceptor]. The forced-host provider is
 * injected so the rewrite logic is exercised without the Hilt graph; Robolectric
 * is used only because the rewrite path logs via [com.difft.android.base.log.lumberjack.L].
 *
 * Invariants under test:
 * - Pin THIS client's own API host (request.host == originHost) to the embedded
 *   host while the proxy is active.
 * - NEVER touch absolute-URL requests to other hosts (CDN config/avatars) — the
 *   regression that produced an SSL "Trust anchor not found" on the self-cert
 *   chat host.
 * - No-op when the proxy is off (provider returns null) or already on target.
 */
@RunWith(RobolectricTestRunner::class)
class ProxyHostInterceptorTest {

    private val originHost = "chat.startup.example"
    private val embeddedHost = "chat.chative.im"

    private fun proceedHostFor(
        requestUrl: String,
        originHost: String?,
        forcedHost: String?,
    ): String {
        val interceptor = ProxyHostInterceptor(originHost) { forcedHost }
        val captured = slot<Request>()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns Request.Builder().url(requestUrl).build()
        every { chain.proceed(capture(captured)) } answers {
            Response.Builder()
                .request(captured.captured)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }
        interceptor.intercept(chain)
        return captured.captured.url.host
    }

    @Test
    fun `pins own api host to embedded host when proxy active`() {
        val host = proceedHostFor(
            requestUrl = "https://$originHost/chat/v1/foo",
            originHost = originHost,
            forcedHost = embeddedHost,
        )
        assertEquals(embeddedHost, host)
    }

    @Test
    fun `leaves cdn host untouched when proxy active`() {
        // Absolute-URL request to a public-CA CDN: host != originHost -> must NOT
        // be rewritten to the self-cert chat host (would break TLS trust).
        val cdn = "d3repcs3hxhwgl.cloudfront.net"
        val host = proceedHostFor(
            requestUrl = "https://$cdn/Chative-MultiGlobalConfigureationFile.json",
            originHost = originHost,
            forcedHost = embeddedHost,
        )
        assertEquals(cdn, host)
    }

    @Test
    fun `no rewrite when proxy off`() {
        val host = proceedHostFor(
            requestUrl = "https://$originHost/chat/v1/foo",
            originHost = originHost,
            forcedHost = null,
        )
        assertEquals(originHost, host)
    }

    @Test
    fun `no rewrite when already on target host`() {
        val host = proceedHostFor(
            requestUrl = "https://$embeddedHost/chat/v1/foo",
            originHost = embeddedHost,
            forcedHost = embeddedHost,
        )
        assertEquals(embeddedHost, host)
    }

    @Test
    fun `no rewrite when origin host unknown`() {
        val host = proceedHostFor(
            requestUrl = "https://$originHost/chat/v1/foo",
            originHost = null,
            forcedHost = embeddedHost,
        )
        assertEquals(originHost, host)
    }
}
