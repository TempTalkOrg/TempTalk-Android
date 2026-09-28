package com.difft.android.migration.v2.transfer

import com.difft.android.migration.crypto.BackupCryptoImpl
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.v2.container.AttachmentByteTarget
import com.difft.android.migration.v2.container.ContainerReaderImpl
import com.difft.android.migration.v2.container.ContainerWriterImpl
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.p2p.android.client.SenderHttpClient
import com.difft.android.p2p.android.server.ReceiverHttpServer
import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.io.FileSink
import com.difft.android.p2p.engine.io.FileSource
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.PrepareUploadResponse
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Sink
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I1 fusion test (integration-design I1 validation + Test Inventory T-US1/T-DS1/T-INT1/T-INT2 in
 * loopback form). Pipes a synthesized [ContainerWriterImpl] stream through the [P2pContainerFusion]
 * glue over a REAL loopback TLS socket (`:p2p` `ReceiverHttpServer` ↔ `SenderHttpClient`) into
 * [ContainerReaderImpl.readStreaming], asserting:
 *  - records round-trip (every fake record reaches the receiver's onRecord),
 *  - attachment bytes are byte-identical end-to-end,
 *  - the trailing HMAC verifies,
 *  - a tampered stream THROWS (never reports success),
 *  - NO temp/backup file is created on either end (the no-disk invariant).
 *
 * Robolectric supplies the Android runtime ([android.util.Base64] for the transferKey); the
 * TLS/socket layer is real JSSE on loopback. No devices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class P2pContainerFusionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val crypto = BackupCryptoImpl()
    private val writer = ContainerWriterImpl(crypto)
    private val reader = ContainerReaderImpl(crypto)
    private val fusion = P2pContainerFusion(writer, reader, crypto)

    private val sourceAccount = "u_fusion"
    private val sourceAppVersion = "0.0.0-fusion"

    private var server: ReceiverHttpServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        server?.stop()
        scope.cancel()
    }

    /** Records emitted to the receiver + attachment bytes captured (the I1 "test consumer" sink). */
    private class CapturingConsumer {
        val records = ArrayList<NeutralRecord>()
        val attachments = HashMap<String, ByteArray>()
        fun target(): AttachmentByteTarget = AttachmentByteTarget { ref, _ ->
            object : ByteArrayOutputStream() {
                override fun close() {
                    attachments[ref] = toByteArray()
                    super.close()
                }
            }
        }
    }

    // ── happy path: synthesized container → loopback P2P → readStreaming, no disk ───────────────────

    @Test
    fun `fusion pipes a synthesized container over loopback P2P and round-trips records + attachment`() =
        runBlocking {
            val cert = SelfSignedCert.loadOrCreate(CertStorage(tmp.newFolder("cert-ok")), "127.0.0.1")
            val consumer = CapturingConsumer()
            val receiver = fusion.startReceiver(
                ip = "127.0.0.1",
                scope = scope,
                cert = cert,
                alias = "rx",
                attachmentTarget = consumer.target(),
                onRecord = { consumer.records.add(it) },
                port = 0, // ephemeral free port (avoid 53317 collisions on the build host)
            )
            server = receiverServerOf(receiver)
            assertNotNull(receiver.transferKey, "receiver generated an ephemeral transferKey")
            assertNotNull(receiver.qrPayload.transferKey, "QR carries the transferKey for the sender")

            val records = I1ContainerFixtures.records(recordCount = 6)
            val ok = fusion.sendContainer(
                target = receiver.qrPayload,
                scope = scope,
                records = records,
                attachmentSource = I1ContainerFixtures.attachmentSource(),
                sourceAccount = sourceAccount,
                sourceAppVersion = sourceAppVersion,
            )

            assertTrue(ok, "upload returned 200 (receiver delays 200 until readStreaming verified)")
            val manifest = withTimeout(10_000) { receiver.awaitOutcome() }
            assertEquals(records.size, manifest.recordCount, "all records tallied + HMAC verified")
            assertEquals(1, manifest.attachmentCount, "the one attachment byte frame tallied")
            assertEquals(records.size, consumer.records.size, "every record reached the receiver consumer")
            assertEquals(
                sha256(I1ContainerFixtures.attachmentBytes()),
                sha256(consumer.attachments.getValue(I1ContainerFixtures.ATTACHMENT_REF)),
                "attachment bytes byte-identical end-to-end over the socket",
            )

            // No-disk invariant: NOTHING was written under the cert dir besides the cert itself, and
            // the fusion created no temp/backup file anywhere we control. (The receive sink + send
            // pipe are pure in-memory Okio pipes — see P2pContainerReceiveSink / P2pContainerPipe.)
            assertNoContainerFileOnDisk()
        }

    // ── I2: REAL-export-shaped stream over CHUNKED P2P (no pre-count, no disk) ───────────────────────

    @Test
    fun `I2 sendRealExport streams a record flow over chunked P2P and round-trips records + attachment`() =
        runBlocking {
            val cert = SelfSignedCert.loadOrCreate(CertStorage(tmp.newFolder("cert-chunked")), "127.0.0.1")
            val consumer = CapturingConsumer()
            val receiver = fusion.startReceiver(
                ip = "127.0.0.1",
                scope = scope,
                cert = cert,
                alias = "rx",
                attachmentTarget = consumer.target(),
                onRecord = { consumer.records.add(it) },
                port = 0,
            )
            server = receiverServerOf(receiver)

            // A real-ish export is a lazily-produced Flow whose total length is UNKNOWN up-front —
            // exactly what sendRealExport streams with chunked transfer-encoding (no pre-count). We
            // feed the same fixtures as a cold Flow to exercise the chunked sender end-to-end without
            // needing native WCDB.
            val records = I1ContainerFixtures.records(recordCount = 8)
            val ok = fusion.sendRealExport(
                target = receiver.qrPayload,
                scope = scope,
                records = kotlinx.coroutines.flow.flow { records.forEach { emit(it) } },
                attachmentSource = I1ContainerFixtures.attachmentSource(),
                sourceAccount = sourceAccount,
                sourceAppVersion = sourceAppVersion,
            )

            assertTrue(ok, "chunked upload returned 200 (receiver delays 200 until readStreaming verified)")
            val manifest = withTimeout(10_000) { receiver.awaitOutcome() }
            assertEquals(records.size, manifest.recordCount, "all records tallied + HMAC verified (chunked)")
            assertEquals(1, manifest.attachmentCount, "the one attachment byte frame tallied (chunked)")
            assertEquals(records.size, consumer.records.size, "every record reached the receiver consumer")
            assertEquals(
                sha256(I1ContainerFixtures.attachmentBytes()),
                sha256(consumer.attachments.getValue(I1ContainerFixtures.ATTACHMENT_REF)),
                "attachment bytes byte-identical end-to-end over the CHUNKED socket",
            )
            // No-disk invariant: the chunked path NEVER writes a container/temp file on either end.
            assertNoContainerFileOnDisk()
        }

    // ── tamper path: a corrupted stream MUST throw (never report success) + no disk ─────────────────

    @Test
    fun `fusion rejects a tampered stream — readStreaming throws and the upload is not confirmed`() =
        runBlocking {
            val cert = SelfSignedCert.loadOrCreate(CertStorage(tmp.newFolder("cert-tamper")), "127.0.0.1")
            val consumer = CapturingConsumer()
            // Stand up the server with a TamperingFileSink that flips a byte inside the trailing HMAC
            // region as the body streams — the body decodes cleanly, only the end-of-stream MAC fails.
            val transferKey = "fixed-fusion-transfer-key-256bit-b64=="
            val receiveSink = P2pContainerReceiveSink<com.difft.android.migration.v2.container.ContainerManifest>(
                scope = scope,
            ) { input ->
                reader.readStreaming(input, transferKey, consumer.target()) { consumer.records.add(it) }
            }
            val srv = ReceiverHttpServer(
                cert = cert,
                deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
                sessionManager = SessionManager(),
                pinPolicy = PinPolicy(),
                fileSink = TamperingFileSink(receiveSink),
                requirePin = false,
                onEvent = {},
            )
            val port = srv.start(0)
            server = srv

            // Send a container keyed by the SAME transferKey; the tampering sink corrupts it in flight.
            val records = I1ContainerFixtures.records(recordCount = 4)
            val qr = QrPayload(ip = "127.0.0.1", port = port, fp = cert.sha256Fingerprint, pin = "x", alias = "rx", transferKey = transferKey)
            val ok = runCatching {
                fusion.sendContainer(qr, scope, records, I1ContainerFixtures.attachmentSource(), sourceAccount, sourceAppVersion)
            }.getOrDefault(false)

            assertFalse(ok, "a tampered stream must NOT be confirmed with a 200")
            var succeeded = false
            val threw = runCatching {
                withTimeout(10_000) { receiveSink.result.await() }
                succeeded = true
            }.isFailure
            assertTrue(threw, "readStreaming must surface the tamper as a failure")
            assertFalse(succeeded, "no success signal may follow a tampered stream (caller must not promote)")
            assertNoContainerFileOnDisk()
        }

    // ── the receiver goes away mid-body: the failure reaches the caller, never the scope's handler ──

    @Test
    fun `fusion surfaces a receiver that drops mid-body to the caller, not to the uncaught handler`() =
        runBlocking {
            val cert = SelfSignedCert.loadOrCreate(CertStorage(tmp.newFolder("cert-drop")), "127.0.0.1")
            // The scope a product caller would hand in: any exception escaping a fire-and-forget child
            // lands here — on the device that handler is the process crash.
            val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught += e })
            val transferKey = "fixed-fusion-transfer-key-256bit-b64=="
            val srv = ReceiverHttpServer(
                cert = cert,
                deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
                sessionManager = SessionManager(),
                pinPolicy = PinPolicy(),
                fileSink = DroppingFileSink(),
                requirePin = false,
                onEvent = {},
            )
            val port = srv.start(0)
            server = srv
            try {
                // Enough records that the writer is still mid-stream when the receiver drops.
                val records = I1ContainerFixtures.records(recordCount = 2000)
                val qr = QrPayload(ip = "127.0.0.1", port = port, fp = cert.sha256Fingerprint, pin = "x", alias = "rx", transferKey = transferKey)
                val outcome = runCatching {
                    withTimeout(30_000) {
                        fusion.sendContainer(qr, ownerScope, records, I1ContainerFixtures.attachmentSource(), sourceAccount, sourceAppVersion)
                    }
                }
                assertFalse(outcome.getOrDefault(false), "a dropped upload must not be confirmed")
                assertTrue(uncaught.isEmpty(), "nothing may escape to the owner scope's handler: $uncaught")
            } finally {
                ownerScope.cancel()
            }
        }

    /**
     * An uploader that dies before it opens the body never closes the pipe's read end, and a writer
     * parked on a full buffer cannot be woken by cancelling its coroutine — the sender would sit in
     * "transferring" for ever. Closing the read end is what wakes it.
     */
    @Test
    fun `abortRead wakes a writer parked on a full pipe`() {
        val pipe = P2pContainerPipe(declaredLength = P2pContainerPipe.UNKNOWN_LENGTH, bufferBytes = 64 * 1024)
        val outcome = java.util.concurrent.CompletableFuture<Throwable?>()
        val writer = Thread {
            outcome.complete(
                runCatching {
                    pipe.archiveSink.openStream().use { out -> repeat(64) { out.write(ByteArray(8 * 1024)) } }
                }.exceptionOrNull(),
            )
        }.also { it.start() }
        Thread.sleep(300) // the writer fills the buffer and blocks: nobody reads

        pipe.abortRead()

        val error = outcome.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(error is java.io.IOException, "a blocked write must fail once the read end closes, got $error")
        writer.join(1_000)
    }

    // ── T-US1 / T-DS1: the sender pipe exposes a FileSource; the receive sink bridges push→pull ─────

    @Test
    fun `P2pContainerPipe size matches the pre-counted container length and exposes a forward-only FileSource`() =
        runBlocking {
            val records = I1ContainerFixtures.records(recordCount = 3)
            val counter = ContainerSizeCounter()
            writer.write(
                records = kotlinx.coroutines.flow.flowOf(*records.toTypedArray()),
                sink = counter,
                passphrase = "k",
                attachmentBytes = I1ContainerFixtures.attachmentSource(),
                sourceAccount = sourceAccount,
                sourceAppVersion = sourceAppVersion,
            )
            assertTrue(counter.byteCount > 0, "counter measured a non-empty container")

            val pipe = P2pContainerPipe(declaredLength = counter.byteCount)
            assertEquals(counter.byteCount, pipe.fileSource.size, "pipe FileSource.size == pre-counted length")
            // T-DS1 analog: the source is forward-only (an okio pipe source) — no random-access file.
            val fs: FileSource = pipe.fileSource
            assertEquals("application/octet-stream", fs.mimeType)
        }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Reflectively pull the [ReceiverHttpServer] out of the session so tearDown can stop it. */
    private fun receiverServerOf(session: P2pContainerFusion.ReceiverSession<*>): ReceiverHttpServer {
        val f = P2pContainerFusion.ReceiverSession::class.java.getDeclaredField("server")
        f.isAccessible = true
        return f.get(session) as ReceiverHttpServer
    }

    /** Assert no `.ttmig`/`.ttbk`/temp container file exists under the test temp root. */
    private fun assertNoContainerFileOnDisk() {
        val offenders = tmp.root.walkTopDown()
            .filter { it.isFile }
            .filter { it.name.endsWith(".ttmig") || it.name.endsWith(".ttbk") || it.name.contains("p2p-rx") }
            .toList()
        assertTrue(offenders.isEmpty(), "no-disk invariant: found container file(s) on disk: $offenders")
    }

    /**
     * A [FileSink] decorator that flips one byte in the LAST 5 bytes (inside the trailing 32-byte
     * HMAC) of the streamed container, then forwards the corrupted bytes to the real receive sink.
     * The body frames decode cleanly; only the end-of-stream MAC compare fails — pinning the HMAC
     * gate exactly as the I0 streaming test does.
     */
    /** A receiver whose body sink fails on the first byte — the peer is gone mid-upload. */
    private class DroppingFileSink : FileSink {
        override fun openWrite(meta: FileMetadata): Sink = object : Sink {
            override fun write(source: okio.Buffer, byteCount: Long) {
                throw java.io.IOException("receiver went away")
            }

            override fun flush() = Unit
            override fun timeout(): okio.Timeout = okio.Timeout.NONE
            override fun close() = Unit
        }

        override fun finalizeFile(meta: FileMetadata, expectedSha256: String?): Result<String> =
            Result.failure(IllegalStateException("never finalized"))
    }

    private class TamperingFileSink(private val delegate: P2pContainerReceiveSink<*>) : FileSink {
        override fun openWrite(meta: FileMetadata): Sink {
            // We can't know the total length to target "last 5 bytes" while streaming, so buffer the
            // body, flip a trailing byte, then push it through the delegate. Buffering here is the
            // TEST corrupting bytes in flight — NOT a product path (product streams via the pipe).
            val real = delegate.openWrite(meta)
            return object : okio.ForwardingSink(real) {
                private val captured = okio.Buffer()
                override fun write(source: okio.Buffer, byteCount: Long) {
                    source.read(captured, byteCount) // divert all bytes; flush on close
                }

                override fun close() {
                    val bytes = captured.readByteArray()
                    if (bytes.size >= 5) {
                        val idx = bytes.size - 5
                        bytes[idx] = (bytes[idx].toInt() xor 0x7F).toByte()
                    }
                    real.write(okio.Buffer().apply { write(bytes) }, bytes.size.toLong())
                    real.close()
                }
            }
        }

        override fun finalizeFile(meta: FileMetadata, expectedSha256: String?): Result<String> =
            delegate.finalizeFile(meta, expectedSha256)
    }
}
