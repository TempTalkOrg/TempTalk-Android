package com.difft.android.network

import com.difft.android.network.speedtest.HostSpeedResult
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 * Which host a failure is attributed to (#1396): the host of the network hop the failure happened
 * on, not the construction-time origin. Covers a redirect, a failed resend, a pooled connection
 * and a body that fails after its headers arrived.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class)
class HostFailoverAttributionTest {

    private val fixtures = mutableListOf<ChatPoolFixture>()
    private val executor = Executors.newCachedThreadPool()

    @After
    fun tearDown() {
        fixtures.forEach(ChatPoolFixture::close)
        executor.shutdownNow()
    }

    private fun pool(vararg hosts: String, others: List<String> = emptyList()) =
        ChatPoolFixture(hosts.toList(), others).also(fixtures::add)

    private fun ChatPoolFixture.fetch(client: OkHttpClient, request: Request = request()): String =
        client.newCall(request).execute().use { it.body.string() }

    private fun ChatPoolFixture.redirect(from: String, toHost: String) {
        server(from).enqueue(
            MockResponse.Builder().code(302).addHeader("Location", "http://$toHost/chat/v1/thing").build()
        )
    }

    // T19 — a redirect target that fails before it connects is not attributable: no mark, no resend.
    @Test
    fun `a redirect follow up that fails before connecting is unattributed`() {
        val f = pool(A, B, C)
        f.redirect(from = A, toHost = B)
        f.failDns(B)
        f.ok(C)

        assertFailsWith<UnknownHostException> { f.fetch(f.client(normalize = false)) }

        assertEquals(1, f.requestCount(A))
        assertEquals(0, f.requestCount(C), "no resend after a redirect hop failed")
        assertEquals(listOf(A, B, C), f.eligibleHosts(), "neither the redirecting nor the target host is marked")
    }

    // T19 — a redirect to a host outside the pool that fails after sending marks nothing.
    @Test
    fun `a post send failure on a redirect target outside the pool marks nothing`() {
        val f = pool(A, B, others = listOf(X))
        f.redirect(from = A, toHost = X)
        f.server(X).enqueue(MockResponse.Builder().code(200).headersDelay(5, TimeUnit.SECONDS).build())
        f.ok(B)

        assertFailsWith<SocketTimeoutException> { f.fetch(f.client(normalize = false, readTimeoutMs = 300)) }

        assertEquals(0, f.requestCount(B))
        assertEquals(listOf(A, B), f.eligibleHosts())
    }

    @Test
    fun `a redirect to another pool host that answers is followed and clears that host`() {
        val f = pool(A, B, C)
        f.coordinator.markHostUnavailable(B)
        f.redirect(from = A, toHost = B)
        f.ok(B, "redirected")

        assertEquals("redirected", f.fetch(f.client(normalize = false)))

        assertEquals(listOf(A, B, C), f.eligibleHosts())
    }

    // T20 — the resend target fails at connect: it is classified connect-stage and the next host is tried.
    @Test
    fun `a failed resend is itself connect stage and the next host is tried`() {
        val f = pool(A, B, C)
        f.respond(A, 500)
        f.failDns(B)
        f.ok(C, "from-c")

        assertEquals("from-c", f.fetch(f.client()))

        assertEquals(1, f.requestCount(A))
        assertEquals(0, f.requestCount(B))
        assertEquals(listOf(C), f.eligibleHosts(), "A answered 5xx and B failed at connect: both marked")
    }

    // T21 — routed to B over a pooled connection, B fails while the response is read: B is marked, not A.
    @Test
    fun `a failure on a pooled connection is attributed to the routed host`() {
        val f = pool(A, B, C)
        // B is the best host, so the client built on A is routed to it.
        f.coordinator.publishProbeResults(listOf(up(B), up(A), up(C)), startGeneration = 0L)
        f.ok(B, "first")
        f.server(B).enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        val client = f.client(normalize = false)

        assertEquals("first", f.fetch(client))
        assertFailsWith<IOException> { f.fetch(client) }

        val first = f.server(B).takeRequest()
        val second = f.server(B).takeRequest()
        assertEquals(first.connectionIndex, second.connectionIndex, "the second request reused the connection")
        assertEquals(0, f.requestCount(A))
        assertEquals(listOf(A, C), f.eligibleHosts(), "B is marked, the origin A is not")
    }

    // T23 — headers arrive, then the body stalls past the read timeout: the host is marked after the failure.
    @Test
    fun `a body that stalls after its headers marks the host once and never resends`() {
        val f = pool(A, B, C)
        f.server(A).enqueue(throttledBody())
        f.ok(B, "from-b")
        val client = f.client(normalize = false, readTimeoutMs = 300)

        assertFailsWith<IOException> { f.fetch(client) }

        assertEquals(0, f.requestCount(B), "the call was not resent")
        assertEquals(listOf(B, C), f.eligibleHosts())
        assertEquals("from-b", f.fetch(client), "the next request goes to the next host")
    }

    @Test
    fun `a body that fails marks the host even though its headers cleared an earlier mark`() {
        val f = pool(A, B)
        f.coordinator.markHostUnavailable(A)
        f.server(A).enqueue(throttledBody())

        assertFailsWith<IOException> {
            f.fetch(f.client(routing = false, normalize = false, readTimeoutMs = 300), f.request(routed = true))
        }

        assertEquals(listOf(B), f.eligibleHosts(), "the later body failure wins over the healthy header answer")
    }

    // T23 — a download cancelled by the caller is no host signal.
    @Test
    fun `a download cancelled by the caller marks nothing`() {
        val f = pool(A, B)
        f.server(A).enqueue(throttledBody())
        val call = f.client(normalize = false, readTimeoutMs = 10_000).newCall(f.request())

        val response = call.execute()
        val read = executor.submit<Throwable?> { runCatching { response.body.string() }.exceptionOrNull() }
        Thread.sleep(200)
        call.cancel()

        assertTrue(read.get(5, TimeUnit.SECONDS) is IOException)
        assertEquals(listOf(A, B), f.eligibleHosts())
    }

    // #1396 — a stale pooled connection fails after the send, OkHttp retries it on a new connection
    // and that connect fails: the final error is connect-stage, so A is marked and B answers.
    @Test
    fun `a connect failure after okhttp retried a stale pooled connection is resent`() {
        val f = pool(A, B, C)
        f.ok(A, "first")
        f.server(A).enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        f.ok(B, "from-b")
        val client = f.client(normalize = false, retryOnConnectionFailure = true)

        assertEquals("first", f.fetch(client))
        f.refuseConnections(A)

        assertEquals("from-b", f.fetch(client))

        assertEquals(2, f.requestCount(A), "the second request went out on the pooled connection")
        assertEquals(1, f.requestCount(B))
        assertEquals(listOf(B, C), f.eligibleHosts())
    }

    // #1396 — the stale exchange may have delivered a write (PUT v1/groups creates a group), so no
    // write method is resent elsewhere.
    @Test
    fun `a write is not resent after okhttp retried a stale pooled connection`() {
        listOf("POST", "PUT", "DELETE").forEach { method ->
            val f = pool(A, B, C)
            f.ok(A, "first")
            f.server(A).enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            f.ok(B, "from-b")
            val client = f.client(normalize = false, retryOnConnectionFailure = true)
            assertEquals("first", f.fetch(client))
            f.refuseConnections(A)
            val write = f.request().newBuilder().method(method, "payload".toRequestBody()).build()

            assertFailsWith<IOException>(method) { f.fetch(client, write) }

            assertEquals(2, f.requestCount(A), "$method went out on the pooled connection")
            assertEquals(0, f.requestCount(B), "a possibly delivered $method is never replayed")
            assertEquals(listOf(B, C), f.eligibleHosts(), "the failing host is still marked")
        }
    }

    // #1396 — with OkHttp retries on, a read failure OkHttp does not recover is still post-send.
    @Test
    fun `a read timeout stays post send when okhttp retries are enabled`() {
        val f = pool(A, B, C)
        f.server(A).enqueue(MockResponse.Builder().code(200).headersDelay(5, TimeUnit.SECONDS).build())
        f.ok(B)

        assertFailsWith<SocketTimeoutException> {
            f.fetch(f.client(normalize = false, readTimeoutMs = 300, retryOnConnectionFailure = true))
        }

        assertEquals(0, f.requestCount(B), "post-send failures are never resent")
        assertEquals(listOf(B, C), f.eligibleHosts())
    }

    private fun throttledBody(): MockResponse =
        MockResponse.Builder().code(200).body("x".repeat(100)).throttleBody(10, 3, TimeUnit.SECONDS).build()

    private fun up(host: String) = HostSpeedResult(host = host, latencyMs = 100L, isAvailable = true)

    private companion object {
        const val A = "a.test"
        const val B = "b.test"
        const val C = "c.test"
        const val X = "x.test"
    }
}
