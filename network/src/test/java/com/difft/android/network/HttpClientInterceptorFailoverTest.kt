package com.difft.android.network

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [HttpClientInterceptor] after auth moved out of it (issue #1231): a 401 passes through untouched,
 * and the failover path's last error is per-call state rather than a shared field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class)
class HttpClientInterceptorFailoverTest {

    @get:Rule
    val hiltEntryPoints = HiltEntryPointRule()

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

    // T38 — when every host is exhausted the terminal exception stays meaningful.
    @Test
    fun `an exhausted failover propagates the last hosts exception`() {
        stubUrlManager(hosts = listOf(ORIGIN_HOST, BACKUP_HOST))
        val request = Request.Builder().url(URL).build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            val proceeded = firstArg<Request>()
            if (proceeded.url.host == ORIGIN_HOST) {
                Response.Builder()
                    .request(proceeded)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Server Error")
                    .build()
            } else {
                throw NetworkException(503, "backup down")
            }
        }

        val error = assertFailsWith<NetworkException> { interceptor.intercept(chain) }

        assertEquals(503, error.errorCode)
    }

    private fun throwingChain(code: Int): Interceptor.Chain {
        val request = Request.Builder().url(URL).build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers { throw NetworkException(code, "boom-$code") }
        return chain
    }

    private fun stubUrlManager(hosts: List<String>) {
        val urlManager = mockk<UrlManager>(relaxed = true)
        every { urlManager.getAllHostsRanked() } returns hosts
        val entryPoint = mockk<HttpClientInterceptor.EntryPoint>()
        every { entryPoint.urlManager } returns urlManager
        hiltEntryPoints.put(HttpClientInterceptor.EntryPoint::class.java, entryPoint)
    }

    private companion object {
        const val ORIGIN_HOST = "origin.test.invalid"
        const val BACKUP_HOST = "backup.test.invalid"
        const val URL = "https://origin.test.invalid/v1/thing"
    }
}
