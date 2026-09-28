package com.difft.android.p2p.android.server

import com.difft.android.p2p.android.client.SenderHttpClient
import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.FingerprintTrustManager
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.ByteArrayFileSource
import com.difft.android.p2p.engine.CapturingFileSink
import com.difft.android.p2p.engine.io.FileSink
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okio.Sink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * The receiver holds the `/upload` response until the import is done — minutes of silence on a
 * large history. This runs the REAL receiver (TLS, session, upload handler) against the REAL sender
 * client with a deliberately short read deadline, and shows that the interim `102 Processing` beats
 * are what carry the sender across that silence: with them the verdict arrives, without them the
 * same wait times out.
 *
 * `InterimResponsePassthroughTest` proves the mechanism against a bare socket; this proves our own
 * receiver actually emits it on the real path, at the right moment.
 */
@RunWith(RobolectricTestRunner::class)
class VerdictHeartbeatIntegrationTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private var server: ReceiverHttpServer? = null

    @After fun tearDown() { server?.stop() }

    @Test
    fun `heartbeat carries the sender across a verdict slower than its read deadline`() = runTest {
        val cert = cert("beat")
        val sink = SlowVerdictSink(verdictDelayMs = 900)
        val port = start(cert, sink, heartbeatIntervalMs = 100)
        val client = shortDeadlineClient(cert, port, readTimeoutMs = 300)

        val ok = uploadOnce(client)

        assertTrue("the verdict must arrive despite 900ms of import under a 300ms read deadline", ok)
        assertEquals("the sink saw the body and finalized it", 1, sink.finalizeCalls)
    }

    @Test
    fun `without the heartbeat the same wait trips the deadline`() = runTest {
        // Control: a beat interval far beyond the verdict delay means no interim response ever goes
        // out. Without this the test above would also pass if the deadline were simply not applied.
        val cert = cert("silent")
        val sink = SlowVerdictSink(verdictDelayMs = 900)
        val port = start(cert, sink, heartbeatIntervalMs = 60_000)
        val client = shortDeadlineClient(cert, port, readTimeoutMs = 300)

        try {
            uploadOnce(client)
            fail("expected the read deadline to trip during the silent verdict")
        } catch (expected: IOException) {
            // The silence was read as a dead receiver — exactly what the heartbeat exists to prevent.
        }
    }

    private suspend fun uploadOnce(client: SenderHttpClient): Boolean {
        val payload = "verdict-heartbeat".repeat(64).toByteArray()
        val fileId = UUID.randomUUID().toString()
        val meta = FileMetadata(id = fileId, fileName = "c.bin", size = payload.size.toLong(), fileType = "application/octet-stream")
        val resp = client.prepareUpload(DeviceInfo(alias = "tx", fingerprint = "tx-fp"), mapOf(fileId to meta), pin = null)
        val token = resp.files.getValue(fileId)
        return client.upload(resp.sessionId, fileId, token, ByteArrayFileSource(payload))
    }

    private fun cert(name: String) = SelfSignedCert.loadOrCreate(CertStorage(tempFolder.newFolder(name)), "127.0.0.1")

    private fun start(cert: SelfSignedCert, sink: FileSink, heartbeatIntervalMs: Long): Int {
        val srv = ReceiverHttpServer(
            cert = cert,
            deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
            sessionManager = SessionManager(),
            pinPolicy = PinPolicy(),
            fileSink = sink,
            requirePin = false,
            verdictHeartbeatIntervalMs = heartbeatIntervalMs,
        )
        server = srv
        return srv.start(0)
    }

    /**
     * The production sender pins the cert and waits 15 minutes for the verdict — long enough to hide
     * the effect under test. Same pinning, a deadline shorter than the import, so the beat is the only
     * thing that can carry the call.
     */
    private fun shortDeadlineClient(cert: SelfSignedCert, port: Int, readTimeoutMs: Long): SenderHttpClient {
        val tm = FingerprintTrustManager(cert.sha256Fingerprint)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        return SenderHttpClient(QrPayload(ip = "127.0.0.1", port = port, fp = cert.sha256Fingerprint, pin = "x", alias = "rx")).apply {
            clientOverride = OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, tm)
                .hostnameVerifier { _, _ -> true }
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }
    }

    /** Lands bytes like [CapturingFileSink], then sits on the verdict the way a real import does. */
    private class SlowVerdictSink(private val verdictDelayMs: Long) : FileSink {
        private val delegate = CapturingFileSink()
        var finalizeCalls = 0
            private set

        override fun openWrite(meta: FileMetadata): Sink = delegate.openWrite(meta)

        override fun finalizeFile(meta: FileMetadata, expectedSha256: String?): Result<String> {
            finalizeCalls++
            Thread.sleep(verdictDelayMs)
            return delegate.finalizeFile(meta, expectedSha256)
        }
    }
}
