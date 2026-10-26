package com.difft.android.network

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [HttpClientInterceptor] after auth (issue #1231) and host failover (#1396) moved out of it: a 401
 * passes through untouched, a failure is per-call state rather than a shared field, and the final
 * answer failover hands it is normalized. Failover itself is covered by [HostFailoverInterceptorTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class)
class HttpClientInterceptorFailoverTest {

    private val interceptor = HttpClientInterceptor(pinnedConnection = false)

    // T36 — a 401 carrying a `reason` must NOT be rewritten to 200.
    @Test
    fun `a 401 with a reason body is not rewritten to 200`() {
        val request = Request.Builder().url(URL).build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("""{"reason":"nope"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val response = interceptor.intercept(chain)

        assertEquals(401, response.code)
        assertEquals("""{"reason":"nope"}""", response.body?.string())
    }

    // T37 — two concurrent calls on ONE interceptor instance never surface each other's error.
    @Test
    fun `concurrent failures do not surface each others exception`() {
        val start = CountDownLatch(1)
        val results = arrayOfNulls<Int>(2)
        val threads = listOf(418, 419).mapIndexed { index, code ->
            Thread {
                start.await()
                val error = runCatching { interceptor.intercept(throwingChain(code)) }.exceptionOrNull()
                results[index] = (error as? NetworkException)?.errorCode
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }

        assertEquals(418, results[0])
        assertEquals(419, results[1])
    }

    // A 5xx that failover could not resolve on any host is the final failure, never a response.
    @Test
    fun `a 5xx that reaches normalization becomes the generic network exception`() {
        val request = Request.Builder().url(URL).build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Unavailable")
                .body("""{"reason":"ignored"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val error = assertFailsWith<NetworkException> { interceptor.intercept(chain) }

        assertEquals(0, error.errorCode)
    }

    // The terminal verdict of a 4xx without a reason keeps its code.
    @Test
    fun `a 404 without a reason becomes a network exception carrying its code`() {
        val request = Request.Builder().url(URL).build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val error = assertFailsWith<NetworkException> { interceptor.intercept(chain) }

        assertEquals(404, error.errorCode)
    }

    @Test
    fun `a 4xx carrying a reason is normalized to a 200`() {
        val request = Request.Builder().url(URL).build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(409)
                .message("Conflict")
                .body("""{"status":1,"reason":"busy"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertEquals("""{"status":1,"reason":"busy"}""", response.body.string())
    }

    // A transport failure that failover rethrows is mapped to the localized generic error.
    @Test
    fun `an io failure is mapped to the generic network error`() {
        val request = Request.Builder().url(URL).build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers { throw java.net.UnknownHostException("origin.test.invalid") }

        assertFailsWith<java.io.IOException> { interceptor.intercept(chain) }
    }

    private fun throwingChain(code: Int): Interceptor.Chain {
        val request = Request.Builder().url(URL).build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers { throw NetworkException(code, "boom-$code") }
        return chain
    }

    private companion object {
        const val URL = "https://origin.test.invalid/v1/thing"
    }
}
