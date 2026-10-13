package com.difft.android.p2p.android.client

import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.model.ReversePairDevice
import com.difft.android.p2p.engine.model.ReversePairListen
import com.difft.android.p2p.engine.model.ReversePairPrecheck
import com.difft.android.p2p.engine.model.ReversePairRequest
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reverse-pairing call against a real pinned-TLS peer.
 *
 * Two properties matter here and neither is visible from the DTOs. The peer holds the response for
 * as long as its user takes to confirm, punctuated by `102 Processing`, so the idle deadline must be
 * restarted by every interim response or a perfectly healthy confirmation dies at 30 s. And every
 * documented status has to arrive as its own reason token, or the receive screen collapses "update
 * your computer", "cancelled there" and "busy" into one generic failure.
 */
@RunWith(RobolectricTestRunner::class)
class ReversePairClientTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val sockets = mutableListOf<ServerSocket>()

    @After fun tearDown() = sockets.forEach { runCatching { it.close() } }

    @Test
    fun `interim 102 beats keep a long confirmation hang alive`() {
        // Six 400 ms gaps, each ended by a 102: 2.4 s of waiting under a 700 ms idle deadline, which
        // only survives if every interim response restarts the clock.
        val client = serve(interimCount = 6, gapMs = 400, status = "200 OK", body = """{"sessionId":"s1"}""", idleMs = 700)

        val response = runBlocking { client.reversePair(request(), PIN) }

        assertEquals("s1", response.sessionId)
    }

    @Test
    fun `the same wait with no interim beats does trip the idle deadline`() {
        // The control: without it the test above would pass even if no deadline were applied at all.
        val client = serve(interimCount = 0, gapMs = 1_200, status = "200 OK", body = """{"sessionId":"s1"}""", idleMs = 700)

        val outcome = runCatching { runBlocking { client.reversePair(request(), PIN) } }

        assertTrue("a silent peer must trip the idle deadline", outcome.isFailure)
    }

    @Test
    fun `every documented status maps to its own reason token`() {
        assertReason("400 Bad Request", """{"error":"unsupported-protocol"}""", "unsupported-protocol")
        assertReason("401 Unauthorized", "", PrepareUploadReasons.PIN_REQUIRED)
        assertReason("403 Forbidden", "desktop-cancelled", "desktop-cancelled")
        assertReason("404 Not Found", "", PrepareUploadReasons.UNSUPPORTED_PROTOCOL)
        assertReason("408 Request Timeout", "", PrepareUploadReasons.PEER_CONFIRM_TIMEOUT)
        assertReason("409 Conflict", "", PrepareUploadReasons.TOO_MANY_SESSIONS)
    }

    @Test
    fun `the client budgets five minutes in total and thirty seconds of silence`() {
        val cert = SelfSignedCert.loadOrCreate(CertStorage(tempFolder.newFolder("certBudget")), "127.0.0.1")

        val client = ReversePairClient(ip = "127.0.0.1", port = 1, fingerprint = cert.sha256Fingerprint).client

        assertEquals(TimeUnit.MINUTES.toMillis(ReversePairClient.HANG_TIMEOUT_MINUTES).toInt(), client.callTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(ReversePairClient.IDLE_TIMEOUT_SECONDS).toInt(), client.readTimeoutMillis)
        // Not the upload client's shape: that one waits 15 minutes for an import verdict.
        assertTrue(client.readTimeoutMillis < TimeUnit.MINUTES.toMillis(UPLOAD_VERDICT_TIMEOUT_MINUTES))
    }

    private fun assertReason(status: String, body: String, expected: String) {
        val client = serve(interimCount = 0, gapMs = 0, status = status, body = body, idleMs = DEFAULT_IDLE_MS)

        val failure = runCatching { runBlocking { client.reversePair(request(), PIN) } }.exceptionOrNull()

        assertTrue("$status must be a typed rejection, was $failure", failure is PrepareUploadRejectedException)
        assertEquals(status, expected, (failure as PrepareUploadRejectedException).reason)
    }

    private fun request() = ReversePairRequest(
        device = ReversePairDevice(alias = "phone", appVersion = "1.0.0", fingerprint = "fp"),
        listen = ReversePairListen(addresses = listOf("127.0.0.1"), port = 53317),
        pairToken = "token",
        precheck = ReversePairPrecheck(env = "test", formatVersion = "1.0", account = "u_a"),
    )

    /**
     * Stands up a one-shot TLS peer that answers [interimCount] `102`s, each after [gapMs], then
     * [status], and returns a client pinned to its certificate with the idle deadline shortened to
     * [idleMs] so the test runs in milliseconds rather than the production 30 s.
     */
    private fun serve(
        interimCount: Int,
        gapMs: Long,
        status: String,
        body: String,
        idleMs: Long,
    ): ReversePairClient {
        val cert = SelfSignedCert.loadOrCreate(CertStorage(tempFolder.newFolder("cert${sockets.size}")), "127.0.0.1")
        val socket = cert.sslServerSocketFactory().createServerSocket(0)
        sockets += socket
        thread(isDaemon = true) {
            runCatching {
                socket.accept().use { peer ->
                    // Drain whatever of the head + body arrives; neither is under test here.
                    peer.getInputStream().read(ByteArray(BUFFER_BYTES))
                    val out = peer.getOutputStream()
                    repeat(interimCount) {
                        Thread.sleep(gapMs)
                        out.write("HTTP/1.1 102 Processing\r\n\r\n".toByteArray())
                        out.flush()
                    }
                    if (interimCount == 0 && gapMs > 0) Thread.sleep(gapMs)
                    out.write(
                        "HTTP/1.1 $status\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                            .toByteArray(),
                    )
                    out.flush()
                }
            }.onFailure { if (it !is SocketException) throw it }
        }
        return ReversePairClient(
            ip = "127.0.0.1",
            port = socket.localPort,
            fingerprint = cert.sha256Fingerprint,
        ).apply { clientOverride = client.newBuilder().readTimeout(idleMs, TimeUnit.MILLISECONDS).build() }
    }

    private companion object {
        const val PIN = "482913"
        const val BUFFER_BYTES = 8 * 1024
        const val DEFAULT_IDLE_MS = 5_000L

        /** [SenderHttpClient]'s verdict wait, quoted for contrast only. */
        const val UPLOAD_VERDICT_TIMEOUT_MINUTES = 15L
    }
}
