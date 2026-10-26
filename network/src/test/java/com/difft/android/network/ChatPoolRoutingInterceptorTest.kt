package com.difft.android.network

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Per-request routing of [ChatPoolRoutingInterceptor] (#1396). The [UrlManager] is a stub, so the
 * rewrite is exercised without the Hilt graph; Robolectric is used only because routing logs via
 * [com.difft.android.base.log.lumberjack.L].
 *
 * Invariants under test:
 * - A request on THIS client's own origin host goes to the live host, however the client was built.
 * - The service path prefix is replaced only when the request path starts with it.
 * - NEVER touch absolute-URL requests to other hosts (CDN config/avatars): the regression that
 *   produced an SSL "Trust anchor not found" on the self-cert chat host (#948).
 */
@RunWith(RobolectricTestRunner::class)
class ChatPoolRoutingInterceptorTest {

    private val urlManager = mockk<UrlManager>()

    @Before
    fun setUp() {
        every { urlManager.currentHostFor(any()) } returns LIVE_HOST
        every { urlManager.servicePathFor(ChatService.CHAT) } returns "/chat"
        every { urlManager.servicePathFor(ChatService.DEFAULT) } returns ""
        every { urlManager.isChatPoolHost(any()) } returns false
        every { urlManager.isChatPoolHost(OTHER_POOL_HOST) } returns true
    }

    private fun proceeded(
        requestUrl: String,
        origin: String = "https://$ORIGIN_HOST/chat/",
        service: ChatService = ChatService.CHAT,
    ): Request {
        val interceptor = ChatPoolRoutingInterceptor(origin.toHttpUrl(), service) { urlManager }
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
        return captured.captured
    }

    // T1 — a client built on host A routes to the live host and the live service path.
    @Test
    fun `routes the own origin to the live host and the live service path`() {
        every { urlManager.servicePathFor(ChatService.CHAT) } returns "/chat2"

        val request = proceeded("https://$ORIGIN_HOST/chat/v1/foo?x=1")

        assertEquals("https://$LIVE_HOST/chat2/v1/foo?x=1", request.url.toString())
        assertEquals(RoutedHost(LIVE_HOST, ChatService.CHAT), request.tag(RoutedHost::class.java))
    }

    // T1b — root-relative APIs on the chat client keep their path and are still routed.
    @Test
    fun `a root relative path on the chat client keeps its path and is routed`() {
        every { urlManager.servicePathFor(ChatService.CHAT) } returns "/chat2"

        val request = proceeded("https://$ORIGIN_HOST/v1/messages")

        assertEquals("https://$LIVE_HOST/v1/messages", request.url.toString())
        assertEquals(RoutedHost(LIVE_HOST, ChatService.CHAT), request.tag(RoutedHost::class.java))
    }

    @Test
    fun `the default client routes the host and never rewrites a path`() {
        val request = proceeded(
            requestUrl = "https://$ORIGIN_HOST/chat/v1/foo",
            origin = "https://$ORIGIN_HOST/",
            service = ChatService.DEFAULT,
        )

        assertEquals("https://$LIVE_HOST/chat/v1/foo", request.url.toString())
        assertEquals(ChatService.DEFAULT, request.tag(RoutedHost::class.java)?.service)
    }

    @Test
    fun `a path that only shares a prefix with the origin path is not rewritten`() {
        every { urlManager.servicePathFor(ChatService.CHAT) } returns "/chat2"

        val request = proceeded("https://$ORIGIN_HOST/chatter/v1/foo")

        assertEquals("https://$LIVE_HOST/chatter/v1/foo", request.url.toString())
    }

    // An absolute URL on another pool host (UrlManager.gifs) keeps its URL and is tagged for failover.
    @Test
    fun `an absolute url on another pool host keeps its url and is tagged`() {
        val url = "https://$OTHER_POOL_HOST/gifs/v1/gifs/trending"

        val request = proceeded(url)

        assertEquals(url, request.url.toString())
        assertEquals(OTHER_POOL_HOST, request.tag(RoutedHost::class.java)?.host)
    }

    // T2 — an absolute CDN URL is neither rewritten nor tagged.
    @Test
    fun `leaves an absolute url on another host untouched and untagged`() {
        val cdn = "d3repcs3hxhwgl.cloudfront.net"

        val request = proceeded("https://$cdn/Chative-MultiGlobalConfigureationFile.json")

        assertEquals(cdn, request.url.host)
        assertNull(request.tag(RoutedHost::class.java))
    }

    @Test
    fun `an already current host is still tagged`() {
        every { urlManager.currentHostFor(any()) } returns ORIGIN_HOST

        val request = proceeded("https://$ORIGIN_HOST/chat/v1/foo")

        assertEquals("https://$ORIGIN_HOST/chat/v1/foo", request.url.toString())
        assertEquals(RoutedHost(ORIGIN_HOST, ChatService.CHAT), request.tag(RoutedHost::class.java))
    }

    @Test
    fun `routing is resolved per request`() {
        val first = proceeded("https://$ORIGIN_HOST/chat/v1/foo")
        every { urlManager.currentHostFor(any()) } returns "other.host.test"
        val second = proceeded("https://$ORIGIN_HOST/chat/v1/foo")

        assertEquals(LIVE_HOST, first.url.host)
        assertEquals("other.host.test", second.url.host)
    }

    @Test
    fun `a failed resolution leaves the request as it was`() {
        every { urlManager.currentHostFor(any()) } throws IllegalStateException("graph not ready")

        val request = proceeded("https://$ORIGIN_HOST/chat/v1/foo")

        assertEquals("https://$ORIGIN_HOST/chat/v1/foo", request.url.toString())
        assertNull(request.tag(RoutedHost::class.java))
    }

    private companion object {
        const val ORIGIN_HOST = "chat.startup.example"
        const val LIVE_HOST = "chat.live.example"
        const val OTHER_POOL_HOST = "chat.other.example"
    }
}
