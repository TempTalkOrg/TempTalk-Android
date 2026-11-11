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
                spoolDir = tmp.newFolder("spool-${System.nanoTime()}"),
                port = 0, 
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

            
            
            
            assertNoContainerFileOnDisk()
        }

    

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
                spoolDir = tmp.newFolder("spool-${System.nanoTime()}"),
                port = 0,
            )
            server = receiverServerOf(receiver)

            
            
            
            
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
            
            assertNoContainerFileOnDisk()
        }

    

    @Test
    fun `fusion rejects a tampered stream — readStreaming throws and the upload is not confirmed`() =
        runBlocking {
            val cert = SelfSignedCert.loadOrCreate(CertStorage(tmp.newFolder("cert-tamper")), "127.0.0.1")
            val consumer = CapturingConsumer()
            
            
            val transferKey = "fixed-fusion-transfer-key-256bit-b64=="
            val receiveSink = P2pContainerReceiveSink<com.difft.android.migration.v2.container.ContainerManifest>(
                scope = scope,
                spoolDir = tmp.newFolder("spool-${System.nanoTime()}"),
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

    

    @Test
    fun `fusion surfaces a receiver that drops mid-body to the caller, not to the uncaught handler`() =
        runBlocking {
            val cert = SelfSignedCert.loadOrCreate(CertStorage(tmp.newFolder("cert-drop")), "127.0.0.1")
            
            
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
        Thread.sleep(300) 

        pipe.abortRead()

        val error = outcome.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(error is java.io.IOException, "a blocked write must fail once the read end closes, got $error")
        writer.join(1_000)
    }

    

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
            
            val fs: FileSource = pipe.fileSource
            assertEquals("application/octet-stream", fs.mimeType)
        }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    
    private fun receiverServerOf(session: P2pContainerFusion.ReceiverSession<*>): ReceiverHttpServer {
        val f = P2pContainerFusion.ReceiverSession::class.java.getDeclaredField("server")
        f.isAccessible = true
        return f.get(session) as ReceiverHttpServer
    }

    
    private fun assertNoContainerFileOnDisk() {
        val offenders = tmp.root.walkTopDown()
            .filter { it.isFile }
            .filter { it.name.endsWith(".ttmig") || it.name.endsWith(".ttbk") || it.name.contains("p2p-rx") }
            .toList()
        assertTrue(offenders.isEmpty(), "no-disk invariant: found container file(s) on disk: $offenders")
    }

    





    
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
            
            
            
            val real = delegate.openWrite(meta)
            return object : okio.ForwardingSink(real) {
                private val captured = okio.Buffer()
                override fun write(source: okio.Buffer, byteCount: Long) {
                    source.read(captured, byteCount) 
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
