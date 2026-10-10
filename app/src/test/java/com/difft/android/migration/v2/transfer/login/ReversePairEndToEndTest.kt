package com.difft.android.migration.v2.transfer.login

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.crypto.BackupCryptoImpl
import com.difft.android.migration.v2.container.ArchiveSink
import com.difft.android.migration.v2.container.AttachmentByteTarget
import com.difft.android.migration.v2.container.ContainerManifest
import com.difft.android.migration.v2.container.ContainerReaderImpl
import com.difft.android.migration.v2.container.ContainerWriterImpl
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.transfer.I1ContainerFixtures
import com.difft.android.migration.v2.transfer.MigrationTransferStateHolder
import com.difft.android.migration.v2.transfer.P2pContainerFusion
import com.difft.android.migration.v2.transfer.TransferState
import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.model.ReversePairResponse
import com.difft.android.p2p.engine.session.PairingSessionRegistry
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T43 — reverse pairing end to end over a REAL loopback
 * [com.difft.android.p2p.android.server.ReceiverHttpServer]: a fake computer that holds
 * `/reverse-pair` open, opens `/prepare-upload` with the pair token **before** it answers 200, and
 * then uploads a real container on that session.
 *
 * What this pins is the race the registration-before-the-request design exists for — the pairing
 * branch of `/prepare-upload` authorizes a peer this device has not yet heard a 200 from — plus the
 * key seam behind it: the registry hands the receive path the transfer key scanned off the
 * computer's code, not the one this listener minted for its own (never shown) QR.
 *
 * The body is consumed by the byte-pipe receiver rather than the real importer, so the assertions
 * stay about the pairing handshake; the streaming import itself is covered by the fusion and
 * orchestrator tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReversePairEndToEndTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val crypto = BackupCryptoImpl()
    private val writer = ContainerWriterImpl(crypto)
    private val fusion = P2pContainerFusion(writer, ContainerReaderImpl(crypto), crypto)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val macPin = "246813"
    private val macTransferKey = "bWFjLXNjYW5uZWQta2V5"
    private val account = "+12025550100"

    private var session: P2pContainerFusion.ReceiverSession<ContainerManifest>? = null

    @Before
    fun setUp() = MigrationTransferStateHolder.reset()

    @After
    fun tearDown() {
        session?.stop()
        scope.cancel()
        MigrationTransferStateHolder.reset()
    }

    private class FakeEnvironment : EnvironmentHelper {
        override val currentEnvironment: String = "prod"
        override val ENVIRONMENT_ONLINE: String = "prod"
        override val ENVIRONMENT_DEVELOPMENT: String = "dev"
        override fun isThatEnvironment(environment: String): Boolean = currentEnvironment == environment
        override fun isGoogleChannel(): Boolean = false
        override fun isInsiderChannel(): Boolean = false
        override fun getChannelName(): String = "official"
        override fun getScheme(): String = "quicall"
    }

    /** Collects the container into memory so it can be uploaded as one body. */
    private class MemorySink : ArchiveSink {
        val bytes = ByteArrayOutputStream()
        override fun openStream(): OutputStream = bytes
        override fun abort() = Unit
    }

    @Test
    fun `a prepare-upload that beats the 200 is accepted and its body completes the receive`() = runBlocking {
        val received = ArrayList<NeutralRecord>()
        val receiver = startReceiver("cert-ok") { received += it }
        val port = receiver.qrPayload.port
        val registry = receiver.pairingRegistry
        val container = containerBytes(receiver.transferKey)

        val controller = ReversePairController(
            appContext = ApplicationProvider.getApplicationContext<Application>(),
            registry = registry,
            environmentHelper = FakeEnvironment(),
            appVersion = "9.9.9",
            account = account,
            resolve = { it },
            pair = { _, body ->
                // The computer connects back and uploads while its own /reverse-pair is still hanging.
                connectBackAndUpload(port, body.pairToken, container)
                ReversePairResponse(sessionId = "mac-session", connectWithinMs = 30_000L)
            },
        )
        withTimeout(TEST_TIMEOUT_MS) {
            // run() hands the outcome over the moment the peer is authorized, which is well before the
            // body is in — so the receive's own outcome is what the assertions below wait for.
            controller.run(scope = scope, mac = macQr(port, receiver.qrPayload.fp), listen = receiver.qrPayload)
            receiver.awaitOutcome()
        }

        assertEquals(PairingSessionRegistry.Phase.UPLOADING, registry.phase(FIRST_GENERATION))
        assertEquals(
            macTransferKey,
            registry.acceptedTransferKey(),
            "the receive path resolves the key from the scanned code, not the locally minted one",
        )
        assertTrue(received.isNotEmpty(), "the container was read and verified")
        assertFalse(
            MigrationTransferStateHolder.state.value is TransferState.Failed,
            "an accepted connect-back must never produce a failure",
        )
    }

    @Test
    fun `a prepare-upload quoting a stale pair token is refused with 403 pair-token`() = runBlocking {
        val receiver = startReceiver("cert-stale") { }
        receiver.pairingRegistry.register(pin = macPin, transferKey = macTransferKey, pairToken = "the-live-token")

        val reply = prepareUpload(receiver.qrPayload.port, "a-token-from-a-cancelled-pairing", size = 0L)
        assertEquals(403, reply.status)
        assertEquals("pair-token", reply.body.trim())
    }

    private fun startReceiver(certDir: String, onRecord: (NeutralRecord) -> Unit): P2pContainerFusion.ReceiverSession<ContainerManifest> {
        val cert = SelfSignedCert.loadOrCreate(CertStorage(tmp.newFolder(certDir)), "127.0.0.1")
        val started = fusion.startReceiver(
            ip = "127.0.0.1",
            scope = scope,
            cert = cert,
            alias = "phone",
            attachmentTarget = AttachmentByteTarget { _, _ -> ByteArrayOutputStream() },
            onRecord = { onRecord(it) },
            spoolDir = tmp.newFolder("spool-${System.nanoTime()}"),
            port = 0,
            requirePin = true,
        )
        session = started
        return started
    }

    private fun macQr(port: Int, fingerprint: String) = QrPayload(
        ip = "127.0.0.1",
        port = port,
        fp = fingerprint,
        pin = macPin,
        alias = "desk",
        transferKey = macTransferKey,
    )

    private suspend fun containerBytes(transferKey: String): ByteArray {
        val sink = MemorySink()
        writer.write(
            records = I1ContainerFixtures.records(recordCount = 4).asFlow(),
            sink = sink,
            passphrase = transferKey,
            attachmentBytes = I1ContainerFixtures.attachmentSource(),
            sourceAccount = account,
            sourceAppVersion = "9.9.9",
        )
        return sink.bytes.toByteArray()
    }

    /** The fake computer's two calls, in the order spec §3.1 allows: prepare-upload, then upload. */
    private fun connectBackAndUpload(port: Int, pairToken: String, container: ByteArray) {
        val prepared = prepareUpload(port, pairToken, container.size.toLong())
        assertEquals(200, prepared.status, prepared.body)
        val sessionId = prepared.body.field("sessionId")
        val fileToken = prepared.body.substringAfter("\"files\"").field(FILE_ID)
        val upload = request(
            port = port,
            method = "POST",
            target = "/api/localsend/v2/upload?sessionId=$sessionId&fileId=$FILE_ID&token=$fileToken",
            body = container,
        )
        assertEquals(200, upload.status, upload.body)
    }

    /**
     * The request body is written by hand: `:app` does not have kotlinx-serialization on its
     * classpath, and this is the one place a test has to speak the peer's side of the wire.
     */
    private fun prepareUpload(port: Int, pairToken: String, size: Long): Reply {
        val body = """
            {"info":{"alias":"desk","version":"2.1","deviceType":"desktop","fingerprint":"mac-fp",
            "port":$port,"protocol":"https","download":false,"announce":false},
            "files":{"$FILE_ID":{"id":"$FILE_ID","fileName":"container.ttmig","size":$size,
            "fileType":"application/octet-stream"}},"pairToken":"$pairToken"}
        """.trimIndent().replace("\n", "")
        return request(port, "POST", "/api/localsend/v2/prepare-upload?pin=$macPin", body.toByteArray())
    }

    /** The first `"<name>":"<value>"` in this JSON text. */
    private fun String.field(name: String): String =
        Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"([^\"]+)\"").find(this)?.groupValues?.get(1)
            ?: error("no $name in $this")

    private class Reply(val status: Int, val body: String)

    /** One request, whole response: the server closes the connection, so the body ends at EOF. */
    private fun request(port: Int, method: String, target: String, body: ByteArray): Reply =
        trustAllSocket(port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.getOutputStream().apply {
                write(
                    (
                        "$method $target HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                            "Content-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                write(body)
                flush()
            }
            val text = socket.getInputStream().bufferedReader().readText()
            Reply(text.substringAfter("HTTP/1.1 ").take(3).toInt(), text.substringAfter("\r\n\r\n", ""))
        }

    /** The pin is not the subject here — accept whatever certificate the loopback server presents. */
    private fun trustAllSocket(port: Int): Socket {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        return ctx.socketFactory.createSocket("127.0.0.1", port)
    }

    private companion object {
        const val FILE_ID = "container-1"
        const val FIRST_GENERATION = 1L
        const val SOCKET_TIMEOUT_MS = 20_000
        const val TEST_TIMEOUT_MS = 60_000L
    }
}
