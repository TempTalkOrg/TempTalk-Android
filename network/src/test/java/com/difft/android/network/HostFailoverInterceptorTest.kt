package com.difft.android.network

import com.difft.android.base.network.NetworkRiskNotifier
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import mockwebserver3.MockResponse
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Classification and resend rules of [HostFailoverInterceptor] on real sockets (#1396): one
 * MockWebServer per virtual host, the real [UrlManager] and coordinator, and the production
 * interceptor order from [ChatPoolFixture.client].
 *
 * Marks are read back through the coordinator ([ChatPoolFixture.eligibleHosts]): a host missing
 * from it was marked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class)
class HostFailoverInterceptorTest {

    private val fixtures = mutableListOf<ChatPoolFixture>()
    private val executor = Executors.newCachedThreadPool()

    @After
    fun tearDown() {
        fixtures.forEach(ChatPoolFixture::close)
        executor.shutdownNow()
    }

    private fun pool(
        vararg hosts: String,
        others: List<String> = emptyList(),
        tls: Boolean = false,
        untrusted: Set<String> = emptySet(),
        tunnel: List<String>? = null,
    ) = ChatPoolFixture(hosts.toList(), others, tls, untrusted, tunnel).also(fixtures::add)

    private fun ChatPoolFixture.call(client: okhttp3.OkHttpClient, request: Request = request()): String =
        client.newCall(request).execute().use { it.body.string() }

    // T3 — connect-stage failure on A (DNS): A is marked and this very call succeeds on B.
    @Test
    fun `a connect failure marks the host and the call succeeds on the next host`() {
        val f = pool(A, B, C)
        f.failDns(A)
        f.ok(B, "from-b")

        val body = f.call(f.client(normalize = false))

        assertEquals("from-b", body)
        assertEquals(0, f.requestCount(A))
        assertEquals(1, f.requestCount(B))
        assertEquals(listOf(B, C), f.eligibleHosts())
    }

    // T3 — a TLS failure is connect-stage too.
    @Test
    fun `a tls failure on the first host is resent to the next`() {
        val f = pool(A, B, tls = true, untrusted = setOf(A))
        f.ok(B, "from-b")

        val body = f.call(f.client(normalize = false))

        assertEquals("from-b", body)
        assertEquals(listOf(B), f.eligibleHosts())
    }

    // T4 — the request was sent and the read timed out: mark, never resend.
    @Test
    fun `a read timeout after the request was sent marks the host and does not resend`() {
        val f = pool(A, B, C)
        f.server(A).enqueue(MockResponse.Builder().code(200).headersDelay(5, TimeUnit.SECONDS).build())
        f.ok(B)

        assertFailsWith<SocketTimeoutException> { f.call(f.client(normalize = false, readTimeoutMs = 300)) }

        assertEquals(1, f.requestCount(A))
        assertEquals(0, f.requestCount(B))
        assertEquals(listOf(B, C), f.eligibleHosts())
    }

    @Test
    fun `a post send failure surfaces as the generic network error when normalized`() {
        val f = pool(A, B)
        f.server(A).enqueue(MockResponse.Builder().code(200).headersDelay(5, TimeUnit.SECONDS).build())

        assertFailsWith<IOException> { f.call(f.client(readTimeoutMs = 300)) }

        assertEquals(0, f.requestCount(B))
        assertEquals(listOf(B), f.eligibleHosts())
    }

    // T5 — A 500, B 404 without a reason: the loop stops at B with B's verdict, C is never tried.
    @Test
    fun `a terminal 4xx from a backup ends the loop with its own verdict`() {
        val f = pool(A, B, C)
        f.respond(A, 500)
        f.respond(B, 404)
        f.ok(C)

        val error = assertFailsWith<NetworkException> { f.call(f.client()) }

        assertEquals(404, error.errorCode)
        assertEquals(0, f.requestCount(C))
        assertEquals(listOf(B, C), f.eligibleHosts(), "A is marked, B answered")
    }

    // T6 — A 500, B 200: B's response, A marked.
    @Test
    fun `a 5xx is resent to the next host and the host is marked`() {
        val f = pool(A, B, C)
        f.respond(A, 503)
        f.ok(B, "from-b")

        assertEquals("from-b", f.call(f.client()))
        assertEquals(listOf(B, C), f.eligibleHosts())
    }

    @Test
    fun `a 5xx is not resent when the request body can only be sent once`() {
        val f = pool(A, B, C)
        f.respond(A, 500)
        f.ok(B)
        val oneShot = object : RequestBody() {
            override fun contentType() = null
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("payload")
            }
        }
        val request = Request.Builder().url("http://$A/chat/v1/upload").post(oneShot).build()

        val error = assertFailsWith<NetworkException> { f.call(f.client(), request) }

        assertEquals(0, error.errorCode)
        assertEquals(0, f.requestCount(B), "the consumed body is never resent")
        assertEquals(listOf(B, C), f.eligibleHosts(), "and the next host is not marked for it")
    }

    @Test
    fun `a connect failure is resent even with a one shot body because nothing was sent`() {
        val f = pool(A, B)
        f.failDns(A)
        f.ok(B, "from-b")
        val oneShot = object : RequestBody() {
            override fun contentType() = null
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("payload")
            }
        }
        val request = Request.Builder().url("http://$A/chat/v1/upload").post(oneShot).build()

        assertEquals("from-b", f.call(f.client(), request))
        assertEquals("payload", f.server(B).takeRequest().body?.utf8())
    }

    @Test
    fun `when every host answers 5xx the call fails with the generic error and availability resets`() {
        val f = pool(A, B, C)
        listOf(A, B, C).forEach { f.respond(it, 502) }

        val error = assertFailsWith<NetworkException> { f.call(f.client()) }

        assertEquals(0, error.errorCode)
        listOf(A, B, C).forEach { assertEquals(1, f.requestCount(it), "$it is tried once") }
        assertEquals(listOf(A, B, C), f.eligibleHosts(), "the exhausted pool was reset")
        assertEquals(1, f.probeLaunches)
    }

    @Test
    fun `a resend after a connect failure that ends in 5xx returns that 5xx`() {
        val f = pool(A, B)
        f.failDns(A)
        f.respond(B, 500, "from-b")

        f.client(normalize = false).newCall(f.request()).execute().use {
            assertEquals(500, it.code)
            assertEquals("from-b", it.body.string())
        }
        assertEquals(1, f.requestCount(B))
    }

    @Test
    fun `the signal client keeps the backup host's raw 5xx after a connect failover`() {
        val f = pool(A, B)
        f.failDns(A)
        f.respond(B, 508, "rejected")

        f.client(normalize = false, resend5xx = false).newCall(f.request()).execute().use {
            assertEquals(508, it.code)
            assertEquals("rejected", it.body.string())
        }
    }

    @Test
    fun `a normalized client turns the final backup 5xx into the generic error`() {
        val f = pool(A, B)
        f.failDns(A)
        f.respond(B, 500)

        val error = assertFailsWith<NetworkException> { f.call(f.client()) }

        assertEquals(0, error.errorCode)
    }

    // T7 — a cancelled call is no host signal.
    @Test
    fun `a cancelled call neither marks nor resends`() {
        val f = pool(A, B, C)
        f.server(A).enqueue(MockResponse.Builder().code(200).headersDelay(5, TimeUnit.SECONDS).build())
        f.ok(B)
        val call = f.client(normalize = false, readTimeoutMs = 10_000).newCall(f.request())

        val result = executor.submit<Throwable?> { runCatching { call.execute().close() }.exceptionOrNull() }
        f.server(A).takeRequest(5, TimeUnit.SECONDS)
        call.cancel()

        assertTrue(result.get(5, TimeUnit.SECONDS) is IOException)
        assertEquals(listOf(A, B, C), f.eligibleHosts())
        assertEquals(0, f.requestCount(B))
    }

    // T8 — a certificate failure on a pinned channel reports the risk for the attempted host.
    @Test
    fun `a certificate failure on a pinned channel notifies the risk and marks the host`() {
        val f = pool(A, B, tls = true, untrusted = setOf(A))
        f.ok(B, "from-b")
        mockkObject(NetworkRiskNotifier)
        try {
            every { NetworkRiskNotifier.onCertValidationFailed(any()) } just Runs

            assertEquals("from-b", f.call(f.client(normalize = false, pinned = true)))

            verify(exactly = 1) { NetworkRiskNotifier.onCertValidationFailed("http:$A") }
            assertEquals(listOf(B), f.eligibleHosts())
        } finally {
            unmockkObject(NetworkRiskNotifier)
        }
    }

    @Test
    fun `a certificate failure on an unpinned channel is not a risk signal`() {
        val f = pool(A, B, tls = true, untrusted = setOf(A))
        f.ok(B)
        mockkObject(NetworkRiskNotifier)
        try {
            every { NetworkRiskNotifier.onCertValidationFailed(any()) } just Runs

            f.call(f.client(normalize = false, pinned = false))

            verify(exactly = 0) { NetworkRiskNotifier.onCertValidationFailed(any()) }
        } finally {
            unmockkObject(NetworkRiskNotifier)
        }
    }

    // T9 — Signal API client: connect failures resend, a 5xx is marked but returned raw.
    @Test
    fun `the signal client resends a connect failure`() {
        val f = pool(A, B)
        f.failDns(A)
        f.ok(B, "from-b")

        assertEquals("from-b", f.call(f.client(normalize = false, resend5xx = false)))
        assertEquals(listOf(B), f.eligibleHosts())
    }

    @Test
    fun `the signal client returns a raw 5xx without a loop`() {
        val f = pool(A, B, C)
        f.respond(A, 500, "raw-body")
        f.ok(B)

        val response = f.client(normalize = false, resend5xx = false).newCall(f.request()).execute()

        response.use {
            assertEquals(500, it.code)
            assertEquals("raw-body", it.body.string())
        }
        assertEquals(0, f.requestCount(B))
        assertEquals(listOf(B, C), f.eligibleHosts())
    }

    // T13 — any answer below 500 from a marked host makes it eligible again.
    @Test
    fun `an answer from a marked host clears its mark`() {
        val f = pool(A, B, C)
        f.coordinator.markHostUnavailable(A)
        f.ok(A)

        f.call(f.client(routing = false, normalize = false), f.request(routed = true))

        assertEquals(listOf(A, B, C), f.eligibleHosts())
    }

    // T13b — a fresh 404 without a reason rehabilitates the host before normalization throws.
    @Test
    fun `a 404 without a reason clears the mark before it becomes a network exception`() {
        val f = pool(A, B, C)
        f.coordinator.markHostUnavailable(A)
        f.respond(A, 404)

        val error = assertFailsWith<NetworkException> {
            f.call(f.client(routing = false), f.request(routed = true))
        }

        assertEquals(404, error.errorCode)
        assertEquals(listOf(A, B, C), f.eligibleHosts())
    }

    // T14 — no usable network: connect failures say nothing about the hosts.
    @Test
    fun `connect failures while offline mark nothing`() {
        val f = pool(A, B, C)
        f.online = false
        f.failDns(A)
        f.failDns(B)

        assertFailsWith<IOException> { f.call(f.client()) }
        f.online = true

        assertEquals(listOf(A, B, C), f.eligibleHosts())
        assertEquals(0, f.probeLaunches)
    }

    // #1396 — offline, every host fails alike: no walk through the pool.
    @Test
    fun `a connect failure while offline is not resent to another host`() {
        val f = pool(A, B, C)
        f.online = false
        f.failDns(A)
        f.ok(B, "from-b")

        assertFailsWith<UnknownHostException> { f.call(f.client(normalize = false)) }
        f.online = true

        assertEquals(0, f.requestCount(B))
        assertEquals(listOf(A, B, C), f.eligibleHosts())
    }

    @Test
    fun `every host failing at connect exhausts the pool, resets it and requests a probe`() {
        val f = pool(A, B, C)
        listOf(A, B, C).forEach(f::failDns)

        assertFailsWith<UnknownHostException> { f.call(f.client(normalize = false)) }

        assertEquals(listOf(A, B, C), f.eligibleHosts())
        assertEquals(1, f.probeLaunches)
    }

    @Test
    fun `the total time bound stops the resend loop`() {
        val f = pool(A, B)
        f.failDns(A)
        f.ok(B)

        assertFailsWith<UnknownHostException> { f.call(f.client(normalize = false, totalBoundMs = 0)) }

        assertEquals(0, f.requestCount(B))
        assertEquals(listOf(B), f.eligibleHosts(), "the failed host is still marked")
    }

    // T17 — under the proxy a failing host rotates only within the tunnel domains.
    @Test
    fun `under the proxy failures rotate only within the tunnel domains`() {
        val f = pool(A, others = listOf(P1, P2), tunnel = listOf(P1, P2))
        f.failDns(P1)
        f.ok(P2, "from-p2")

        assertEquals("from-p2", f.call(f.client(normalize = false)))

        assertEquals(0, f.requestCount(A), "the startup host is never contacted under the proxy")
        assertEquals(listOf(P2), f.eligibleHosts())
    }

    @Test
    fun `under the proxy a call that exhausts the tunnel domains never leaves them`() {
        val f = pool(A, others = listOf(P1, P2), tunnel = listOf(P1, P2))
        f.failDns(P1)
        f.failDns(P2)

        assertFailsWith<UnknownHostException> { f.call(f.client(normalize = false)) }

        assertEquals(0, f.requestCount(A))
    }

    // T2 — an absolute URL to a CDN is no pool call: nothing is rewritten, marked or resent.
    // An absolute URL built on a pool host other than the client origin (UrlManager.gifs) still
    // fails over, as it did before routing moved into its own interceptor.
    @Test
    fun `a 5xx from an absolute url on another pool host fails over`() {
        val f = pool(A, B, C)
        f.respond(B, 503)
        f.ok(A, "from-a")

        val body = f.call(f.client(normalize = false), f.request(path = "/gifs/v1/gifs/trending", host = B))

        assertEquals("from-a", body, "the 5xx on B is resent to the best remaining host")
        assertEquals(1, f.requestCount(B), "the url keeps its host until failover moves it")
        assertEquals(listOf(A, C), f.eligibleHosts())
    }

    @Test
    fun `a 5xx from an absolute cdn url never reaches a chat host`() {
        val f = pool(A, B, C, others = listOf(CDN))
        f.respond(CDN, 503)
        f.ok(A)
        f.ok(B)

        assertFailsWith<NetworkException> { f.call(f.client(), f.request(path = "/files/config.json", host = CDN)) }

        assertEquals(1, f.requestCount(CDN))
        assertEquals(0, f.requestCount(A))
        assertEquals(0, f.requestCount(B))
        assertEquals(listOf(A, B, C), f.eligibleHosts())
    }

    @Test
    fun `a connect failure on a non pool host is not resent`() {
        val f = pool(A, B, others = listOf(CDN))
        f.failDns(CDN)
        f.ok(A)

        assertFailsWith<IOException> { f.call(f.client(), f.request(path = "/x", host = CDN)) }

        assertEquals(0, f.requestCount(A))
        assertEquals(listOf(A, B), f.eligibleHosts())
    }

    // Routing end to end: A marked, the next request goes to B with the live path.
    @Test
    fun `after a host is marked the next request goes to the next host`() {
        val f = pool(A, B, C)
        f.coordinator.markHostUnavailable(A)
        f.ok(B, "from-b")

        assertEquals("from-b", f.call(f.client()))

        assertEquals("/chat/v1/thing", f.server(B).takeRequest().url.encodedPath)
        assertEquals(0, f.requestCount(A))
    }

    private companion object {
        const val A = "a.test"
        const val B = "b.test"
        const val C = "c.test"
        const val P1 = "p1.test"
        const val P2 = "p2.test"
        const val CDN = "cdn.test"
    }
}
