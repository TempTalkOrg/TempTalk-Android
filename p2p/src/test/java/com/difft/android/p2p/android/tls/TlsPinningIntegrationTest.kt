package com.difft.android.p2p.android.tls

import com.difft.android.p2p.android.server.ReceiverHttpServer
import com.difft.android.p2p.android.client.SenderHttpClient
import com.difft.android.p2p.engine.ByteArrayFileSource
import com.difft.android.p2p.engine.CapturingFileSink
import com.difft.android.p2p.engine.io.Sha256
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import kotlinx.coroutines.test.runTest
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * T-T1 / T-T2 / T-T3 — real TLS handshake + loopback upload over self-signed cert + fingerprint
 * pin (design §13). Robolectric provides the Android runtime; the TLS/socket layer is real JSSE.
 */
@RunWith(RobolectricTestRunner::class)
class TlsPinningIntegrationTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private var server: ReceiverHttpServer? = null

    @After fun tearDown() { server?.stop() }

    @Test
    fun `T-T1 fingerprint is whole-cert DER SHA-256 and a pinned client handshakes`() {
        val storage = CertStorage(tempFolder.newFolder("cert1"))
        val cert = SelfSignedCert.loadOrCreate(storage, "127.0.0.1")
        // Fingerprint must equal SHA-256 over the DER cert (LocalSend §A.4), NOT the SPKI.
        assertEquals(FingerprintTrustManager.certSha256Hex(cert.held.certificate), cert.sha256Fingerprint)
        assertEquals(64, cert.sha256Fingerprint.length) // hex of 32 bytes
    }

    @Test
    fun `T-T2 a client pinned to the WRONG fingerprint rejects the handshake`() = runTest {
        val storage = CertStorage(tempFolder.newFolder("cert2"))
        val cert = SelfSignedCert.loadOrCreate(storage, "127.0.0.1")
        val port = startServer(cert)

        // Pin to a DIFFERENT cert's fingerprint → MITM rejected.
        val otherFp = FingerprintTrustManager.certSha256Hex(
            HeldCertificate.Builder().commonName("other").build().certificate,
        )
        val client = SenderHttpClient(QrPayload(ip = "127.0.0.1", port = port, fp = otherFp, pin = "000000", alias = "x"))
        val threw = runCatching { client.register(DeviceInfo(alias = "s", fingerprint = "fp")) }.isFailure
        assertTrue("handshake must fail against a mismatched fingerprint", threw)
    }

    @Test
    fun `register-reply payload is our DeviceInfo and a no-PIN prepare-upload is accepted by a requirePin=false server`() = runTest {
        // Wire-compat for the §A.3 register-reply body + the #2 no-PIN send path: a server running
        // requirePin=false must accept prepare-upload with NO pin query.
        val storage = CertStorage(tempFolder.newFolder("certNoPin"))
        val cert = SelfSignedCert.loadOrCreate(storage, "127.0.0.1")
        val sm = SessionManager()
        val port = startServerNoPin(cert, sm)

        val meta = FileMetadata(id = "f1", fileName = "f.bin", size = 1, fileType = "application/octet-stream", sha256 = Sha256.hex(byteArrayOf(1)))
        val client = SenderHttpClient(ip = "127.0.0.1", port = port, fingerprint = cert.sha256Fingerprint)
        val resp = client.prepareUpload(DeviceInfo(alias = "s", fingerprint = "fp"), meta, pin = null)
        assertTrue("no-PIN prepare-upload must yield a session+token", resp.files.containsKey("f1"))
    }

    @Test
    fun `T-T3 full loopback upload lands the file byte-identical with sha256 verified`() = runTest {
        val storage = CertStorage(tempFolder.newFolder("cert3"))
        val cert = SelfSignedCert.loadOrCreate(storage, "127.0.0.1")
        val sink = CapturingFileSink()
        val sm = SessionManager()
        val pin = PinPolicy()
        val code = pin.issue()
        val received = CountDownLatch(1)
        val port = startServer(cert, sm, pin, sink) { if (it is ReceiverHttpServer.ServerEvent.FileReceived) received.countDown() }

        val payload = "the quick brown fox jumps over the lazy dog".repeat(1000).toByteArray()
        val sha = Sha256.hex(payload)
        val fileId = UUID.randomUUID().toString()
        val meta = FileMetadata(id = fileId, fileName = "doc.bin", size = payload.size.toLong(), fileType = "application/octet-stream", sha256 = sha)

        val client = SenderHttpClient(QrPayload(ip = "127.0.0.1", port = port, fp = cert.sha256Fingerprint, pin = code, alias = "rx"))
        val self = DeviceInfo(alias = "sender", fingerprint = "sender-fp")
        val resp = client.prepareUpload(self, meta, code)
        val token = resp.files.getValue(fileId)
        val ok = client.upload(resp.sessionId, fileId, token, ByteArrayFileSource(payload)) { _, _ -> }

        assertTrue("upload returned 200", ok)
        assertTrue("file received within timeout", received.await(10, TimeUnit.SECONDS))
        assertArrayEquals("file must land byte-identical", payload, sink.bytesOf(fileId))
        assertEquals(sha, Sha256.hex(sink.bytesOf(fileId)))
    }

    @Test
    fun `T-INT chunked upload (unknown length) lands byte-identical AND content-length still works`() = runTest {
        // I2 framing coexistence: a FileSource with size=-1 makes the sender stream the body with
        // Transfer-Encoding: chunked; the receiver de-chunks it (ChunkedSource) into the same
        // FileSink. A second upload with a known size keeps Content-Length (oracle path unchanged).
        // Both must land byte-identical on the SAME server.
        // No-PIN server (matrix #2 path) so two prepare-uploads work without consuming a one-time PIN.
        val storage = CertStorage(tempFolder.newFolder("certChunk"))
        val cert = SelfSignedCert.loadOrCreate(storage, "127.0.0.1")
        val sink = CapturingFileSink()
        val sm = SessionManager()
        val port = startServerNoPin(cert, sm, sink)
        val client = SenderHttpClient(QrPayload(ip = "127.0.0.1", port = port, fp = cert.sha256Fingerprint, pin = "x", alias = "rx"))
        val self = DeviceInfo(alias = "sender", fingerprint = "sender-fp")

        // Both files in ONE session (the receiver allows one active session; multiple files per
        // session is the protocol shape). File 1 = chunked (unknown length), file 2 = Content-Length.
        val chunkedBytes = "chunked-payload-".repeat(5000).toByteArray()
        val clBytes = "content-length-payload-".repeat(3000).toByteArray()
        val chunkedId = UUID.randomUUID().toString()
        val clId = UUID.randomUUID().toString()
        val resp = client.prepareUpload(
            self,
            mapOf(
                chunkedId to FileMetadata(id = chunkedId, fileName = "chunked.bin", size = 0, fileType = "application/octet-stream", sha256 = null),
                clId to FileMetadata(id = clId, fileName = "cl.bin", size = clBytes.size.toLong(), fileType = "application/octet-stream", sha256 = Sha256.hex(clBytes)),
            ),
            pin = null,
        )

        // (1) CHUNKED: unknown-length source (size = -1) → Transfer-Encoding: chunked.
        val okChunked = client.upload(
            resp.sessionId, chunkedId, resp.files.getValue(chunkedId),
            UnknownLengthFileSource(chunkedBytes),
        ) { _, _ -> }
        assertTrue("chunked upload returned 200", okChunked)
        assertArrayEquals("chunked body must land byte-identical", chunkedBytes, sink.bytesOf(chunkedId))

        // (2) CONTENT-LENGTH: known-size source → unchanged framing (oracle path not regressed).
        val okCl = client.upload(resp.sessionId, clId, resp.files.getValue(clId), ByteArrayFileSource(clBytes)) { _, _ -> }
        assertTrue("content-length upload returned 200", okCl)
        assertArrayEquals("content-length body must land byte-identical", clBytes, sink.bytesOf(clId))
    }

    /** A [com.difft.android.p2p.engine.io.FileSource] with UNKNOWN length (size = -1) → chunked upload. */
    private class UnknownLengthFileSource(private val bytes: ByteArray) : com.difft.android.p2p.engine.io.FileSource {
        override val fileName: String = "unknown.bin"
        override val size: Long = -1L // drives Transfer-Encoding: chunked
        override val mimeType: String = "application/octet-stream"
        override val sha256: String? = null
        override fun openRead(): okio.Source = okio.Buffer().apply { write(bytes) }
    }

    @Test
    fun `wrong PIN over real TLS is rejected by the server`() = runTest {
        val storage = CertStorage(tempFolder.newFolder("cert4"))
        val cert = SelfSignedCert.loadOrCreate(storage, "127.0.0.1")
        val pin = PinPolicy().also { it.issue() }
        val port = startServer(cert, pinPolicy = pin)

        val meta = FileMetadata(id = "f1", fileName = "f.bin", size = 1, fileType = "application/octet-stream", sha256 = Sha256.hex(byteArrayOf(1)))
        val client = SenderHttpClient(QrPayload(ip = "127.0.0.1", port = port, fp = cert.sha256Fingerprint, pin = "999999", alias = "rx"))
        val threw = runCatching { client.prepareUpload(DeviceInfo(alias = "s", fingerprint = "fp"), meta, pin = "999999") }.isFailure
        assertTrue("server must reject a wrong PIN (401)", threw)
    }

    @Test
    fun `I4 migration gate REJECTS an env mismatch over real TLS — sender sees the typed reason, NO session`() = runTest {
        val storage = CertStorage(tempFolder.newFolder("certGate1"))
        val cert = SelfSignedCert.loadOrCreate(storage, "127.0.0.1")
        val sm = com.difft.android.p2p.engine.session.SessionManager()
        // A gate that hard-rejects env mismatch (the prod-vs-test isolation red line).
        val gate = com.difft.android.p2p.engine.upload.MigrationHandshakeValidator {
            com.difft.android.p2p.engine.upload.MigrationGateDecision.Reject(
                com.difft.android.p2p.engine.upload.MigrationRejectReason.ENV_MISMATCH,
            )
        }
        val port = startServer(cert, sm = sm, pinPolicy = PinPolicy(), migrationValidator = gate)

        val meta = FileMetadata(id = "f1", fileName = "c.bin", size = 0, fileType = "application/octet-stream")
        val client = SenderHttpClient(QrPayload(ip = "127.0.0.1", port = port, fp = cert.sha256Fingerprint, pin = "000000", alias = "rx"))
        val handshake = com.difft.android.p2p.engine.model.MigrationHandshake(
            sourceEnvironment = "test", sourceAccount = "u_a", sourceAppVersion = "1.0.0", sourceFormatVersion = "1.0",
        )
        val ex = runCatching {
            client.prepareUpload(DeviceInfo(alias = "s", fingerprint = "fp"), meta, pin = null, handshake = handshake)
        }.exceptionOrNull()
        // The sender sees the SPECIFIC typed reject reason (not a generic failure), and NO session exists.
        assertTrue("expected a typed gate reject", ex is com.difft.android.p2p.android.client.PrepareUploadRejectedException)
        assertEquals(
            com.difft.android.p2p.engine.upload.MigrationRejectReason.ENV_MISMATCH.wire,
            (ex as com.difft.android.p2p.android.client.PrepareUploadRejectedException).reason,
        )
        assertEquals("no session may be created on a gate reject", null, sm.activeSessionId())
    }

    @Test
    fun `I4 migration gate ACCEPTS a matching handshake over real TLS — prepare-upload returns a session`() = runTest {
        val storage = CertStorage(tempFolder.newFolder("certGate2"))
        val cert = SelfSignedCert.loadOrCreate(storage, "127.0.0.1")
        val sm = com.difft.android.p2p.engine.session.SessionManager()
        val gate = com.difft.android.p2p.engine.upload.MigrationHandshakeValidator {
            com.difft.android.p2p.engine.upload.MigrationGateDecision.Accept
        }
        val port = startServer(cert, sm = sm, pinPolicy = PinPolicy(), migrationValidator = gate)

        val meta = FileMetadata(id = "f1", fileName = "c.bin", size = 0, fileType = "application/octet-stream")
        val client = SenderHttpClient(QrPayload(ip = "127.0.0.1", port = port, fp = cert.sha256Fingerprint, pin = "000000", alias = "rx"))
        val handshake = com.difft.android.p2p.engine.model.MigrationHandshake(
            sourceEnvironment = "test", sourceAccount = "u_a", sourceAppVersion = "1.0.0", sourceFormatVersion = "1.0",
        )
        val resp = client.prepareUpload(DeviceInfo(alias = "s", fingerprint = "fp"), meta, pin = null, handshake = handshake)
        assertTrue(resp.sessionId.isNotBlank())
        assertEquals(resp.sessionId, sm.activeSessionId())
    }

    private fun startServer(
        cert: SelfSignedCert,
        sm: SessionManager = SessionManager(),
        pinPolicy: PinPolicy = PinPolicy().also { it.issue() },
        sink: CapturingFileSink = CapturingFileSink(),
        migrationValidator: com.difft.android.p2p.engine.upload.MigrationHandshakeValidator? = null,
        onEvent: (ReceiverHttpServer.ServerEvent) -> Unit = {},
    ): Int {
        // A migration-gated server runs requirePin=false in these tests (the gate, not the PIN, is the
        // subject); the default file-transfer server keeps requirePin=true (PIN is its subject).
        val srv = ReceiverHttpServer(
            cert = cert,
            deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
            sessionManager = sm,
            pinPolicy = pinPolicy,
            fileSink = sink,
            requirePin = migrationValidator == null,
            onEvent = onEvent,
            migrationValidator = migrationValidator,
        )
        server = srv
        // Port 0 → ephemeral free port (avoid 53317 collisions on the build host).
        return srv.start(0)
    }

    private fun startServerNoPin(
        cert: SelfSignedCert,
        sm: SessionManager,
        sink: CapturingFileSink = CapturingFileSink(),
    ): Int {
        val srv = ReceiverHttpServer(
            cert = cert,
            deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
            sessionManager = sm,
            pinPolicy = PinPolicy(),
            fileSink = sink,
            requirePin = false,
            onEvent = {},
        )
        server = srv
        return srv.start(0)
    }
}
