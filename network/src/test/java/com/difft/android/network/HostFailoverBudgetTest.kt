package com.difft.android.network

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/** The failover budget caps how long a resend may spend connecting (#1396). */
class HostFailoverBudgetTest {

    private val urlManager = mockk<UrlManager> {
        every { recordFailedHost(any()) } just runs
        every { recordHealthyHost(any()) } just runs
        every { getAllHostsRanked() } returns listOf(A, B)
        every { isNetworkAvailable() } returns true
    }

    private val request = Request.Builder()
        .url("https://$A/chat/v1/thing")
        .tag(RoutedHost::class.java, RoutedHost(A, ChatService.CHAT))
        .build()

    private fun ok(request: Request) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("ok".toByteArray().toResponseBody())
        .build()

    private fun interceptor(now: () -> Long) = HostFailoverInterceptor(
        resendOnServerError = true,
        pinnedConnection = false,
        totalBoundMs = 10_000,
        urlManagerProvider = { urlManager },
        elapsedMs = now,
    )

    @Test
    fun `a resend near the end of the budget connects with the remaining time only`() {
        var now = 0L
        val bounded = mockk<Interceptor.Chain> {
            every { proceed(any()) } answers { ok(firstArg()) }
        }
        val chain = mockk<Interceptor.Chain> {
            every { request() } returns request
            every { call() } returns mockk<Call> { every { isCanceled() } returns false }
            every { connectTimeoutMillis() } returns 15_000
            every { proceed(any()) } answers {
                now = 7_000 // the first host spent 7 s failing to resolve
                throw UnknownHostException(A)
            }
            every { withConnectTimeout(any(), any()) } returns bounded
        }

        val response = interceptor { now }.intercept(chain)

        assertEquals(B, response.request.url.host)
        verify(exactly = 1) { chain.withConnectTimeout(3_000, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `a resend with budget to spare keeps the client's connect timeout`() {
        var calls = 0
        val chain = mockk<Interceptor.Chain> {
            every { request() } returns request
            every { call() } returns mockk<Call> { every { isCanceled() } returns false }
            every { connectTimeoutMillis() } returns 2_000
            every { proceed(any()) } answers {
                if (calls++ == 0) throw UnknownHostException(A) else ok(firstArg())
            }
        }

        val response = interceptor { 0L }.intercept(chain)

        assertEquals(B, response.request.url.host)
        verify(exactly = 0) { chain.withConnectTimeout(any(), any()) }
    }

    private companion object {
        const val A = "a.chat.test"
        const val B = "b.chat.test"
    }
}
