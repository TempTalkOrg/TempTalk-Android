package com.difft.android.network

import android.content.Context
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.proxy.ProxyConfigProvider
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How [ChativeHttpClientModule] wires each client into the chat pool (#1396): which service routes
 * it, and the interceptor order that makes failover see raw responses and normalization see only
 * the final one. The real providers run, so a client added without a service shows up here.
 */
@RunWith(RobolectricTestRunner::class)
class ChativeHttpClientFailoverWiringTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val urlManager = mockk<UrlManager>()
    private val authProvider = mockk<ChativeHttpClient.AuthProvider>(relaxed = true)
    private val tokenProvider = dagger.Lazy { mockk<AuthTokenProvider>(relaxed = true) }
    private val proxy = mockk<ProxyConfigProvider>(relaxed = true)

    @Before
    fun setUp() {
        every { urlManager.default } returns "https://a.test/"
        every { urlManager.chat } returns "https://a.test/chat/"
        every { urlManager.call } returns "https://a.test/call/"
        every { urlManager.fileSharing } returns "https://a.test/fileshare/"
    }

    /** The interceptor chain of the private [OkHttpClient] a [ChativeHttpClient] builds. */
    private fun ChativeHttpClient.okHttp(): OkHttpClient {
        val field = ChativeHttpClient::class.java.getDeclaredField("okHttpClient")
        field.isAccessible = true
        return field.get(this) as OkHttpClient
    }

    private fun OkHttpClient.chain(): List<Interceptor> = interceptors.filterNot { it is HttpLoggingInterceptor }

    private fun OkHttpClient.routedService(): ChatService? =
        interceptors.filterIsInstance<ChatPoolRoutingInterceptor>().singleOrNull()?.service

    @Test
    fun `each pool client routes its own service`() {
        val clients = mapOf(
            ChatService.DEFAULT to ChativeHttpClientModule.provideDefaultClient(context, urlManager, authProvider, tokenProvider, proxy),
            ChatService.CHAT to ChativeHttpClientModule.provideChatClient(context, urlManager, authProvider, tokenProvider, proxy),
            ChatService.CALL to ChativeHttpClientModule.provideMeetingNewClient(context, urlManager, authProvider, tokenProvider, proxy),
            ChatService.FILE_SHARING to ChativeHttpClientModule.provideFileShareClient(context, urlManager, authProvider, tokenProvider, proxy),
        )

        clients.forEach { (service, client) ->
            assertEquals(service, client.okHttp().routedService(), "service of the $service client")
        }
    }

    @Test
    fun `the auth token no header and signal clients route the chat service`() {
        val clients = listOf(
            ChativeHttpClientModule.provideAuthTokenClient(context, urlManager, proxy),
            ChativeHttpClientModule.provideNoHeaderClient(context, urlManager, proxy),
            ChativeHttpClientModule.provideNoHeaderLongTimeoutClient(context, urlManager, proxy),
            ChativeHttpClientModule.provideSignalApiClient(context, urlManager, authProvider, tokenProvider, proxy),
        )

        clients.forEach { assertEquals(ChatService.CHAT, it.okHttp().routedService()) }
    }

    @Test
    fun `a normalized client orders routing, auth, normalization, failover`() {
        val client = ChativeHttpClientModule.provideChatClient(context, urlManager, authProvider, tokenProvider, proxy).okHttp()

        assertEquals(
            listOf(
                ChatPoolRoutingInterceptor::class,
                HeaderInterceptor::class,
                HttpClientInterceptor::class,
                HostFailoverInterceptor::class,
            ),
            client.chain().map { it::class },
        )
        assertTrue(client.networkInterceptors.any { it is ExchangeObserver })
        assertEquals(true, client.interceptors.filterIsInstance<HostFailoverInterceptor>().single().resendOnServerError)
    }

    @Test
    fun `the no header client keeps the same order with its own header stripper`() {
        val client = ChativeHttpClientModule.provideNoHeaderClient(context, urlManager, proxy).okHttp()

        assertEquals(
            listOf(
                ChatPoolRoutingInterceptor::class,
                NoHeaderInterceptor::class,
                HttpClientInterceptor::class,
                HostFailoverInterceptor::class,
            ),
            client.chain().map { it::class },
        )
    }

    @Test
    fun `the signal client fails over connect errors but keeps raw statuses`() {
        val client = ChativeHttpClientModule.provideSignalApiClient(context, urlManager, authProvider, tokenProvider, proxy).okHttp()

        assertEquals(
            listOf(ChatPoolRoutingInterceptor::class, HeaderInterceptor::class, HostFailoverInterceptor::class),
            client.chain().map { it::class },
        )
        assertTrue(client.networkInterceptors.any { it is ExchangeObserver })
        assertEquals(false, client.interceptors.filterIsInstance<HostFailoverInterceptor>().single().resendOnServerError)
    }

    @Test
    fun `a client outside the pool gets no routing and no failover`() {
        val client = ChativeHttpClient(
            context,
            "https://probe.invalid/",
            null,
            useCustomCa = false,
            removeHeader = true,
            useHttpClientInterceptor = false,
        ).okHttp()

        assertEquals(listOf(NoHeaderInterceptor::class), client.chain().map { it::class })
        assertNull(client.routedService())
        assertTrue(client.networkInterceptors.none { it is ExchangeObserver })
    }
}
