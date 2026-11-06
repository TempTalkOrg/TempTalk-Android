package com.difft.android.migration.v2.transfer

import com.difft.android.migration.crypto.BackupCryptoImpl
import com.difft.android.migration.v2.container.AttachmentByteTarget
import com.difft.android.migration.v2.container.ContainerManifest
import com.difft.android.migration.v2.container.ContainerReaderImpl
import com.difft.android.migration.v2.container.ContainerWriterImpl
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.p2p.android.server.ReceiverHttpServer
import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.model.CancelState
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.model.ReceiverCaps
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sender's capability gate (#1382) over a real loopback receiver: a container that needs
 * `pending-envelope-v1` is refused by a receiver that does not advertise it after `prepare-upload`
 * and before any frame, with the session cancelled by name; a receiver that does gets the container.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class P2pSenderCapabilityGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val crypto = BackupCryptoImpl()
    private val reader = ContainerReaderImpl(crypto)
    private val fusion = P2pContainerFusion(ContainerWriterImpl(crypto), reader, crypto)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ReceiverHttpServer? = null
    private val transferKey = "fixed-gate-transfer-key-256bit-b64=="

    @After
    fun tearDown() {
        server?.stop()
        scope.cancel()
    }

    private class RecordingHooks : SendCommitHooks {
        var finalized = false
        var abortAnswers = mutableListOf<String?>()
        override suspend fun beforeFinalize() {
            finalized = true
        }
        override fun onAbortAnswer(answer: String?) {
            abortAnswers += answer
        }
    }

    private class Received {
        val records = ArrayList<NeutralRecord>()
        var bodyOpened = false
    }

    private fun receiver(caps: List<String>, received: Received): QrPayload {
        val cert = SelfSignedCert.loadOrCreate(CertStorage(tmp.newFolder("cert-${System.nanoTime()}")), "127.0.0.1")
        val sink = P2pContainerReceiveSink<ContainerManifest>(scope = scope, spoolDir = tmp.newFolder("spool-${System.nanoTime()}")) { input ->
            received.bodyOpened = true
            reader.readStreaming(input, transferKey, AttachmentByteTarget { _, _ -> OutputStream.nullOutputStream() }) { received.records += it }
        }
        val srv = ReceiverHttpServer(
            cert = cert,
            deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
            sessionManager = SessionManager(),
            pinPolicy = PinPolicy(),
            fileSink = sink,
            requirePin = false,
            caps = caps,
        )
        val port = srv.start(0)
        server = srv
        return QrPayload(ip = "127.0.0.1", port = port, fp = cert.sha256Fingerprint, pin = "x", alias = "rx", transferKey = transferKey)
    }

    private suspend fun send(target: QrPayload, hooks: SendCommitHooks, requiredCaps: Set<String>): Boolean {
        val records = I1ContainerFixtures.records(recordCount = 3)
        return fusion.sendRealExport(
            target = target,
            scope = scope,
            records = flow { records.forEach { emit(it) } },
            attachmentSource = I1ContainerFixtures.attachmentSource(),
            sourceAccount = "u_gate",
            sourceAppVersion = "0.0.0-gate",
            commitHooks = hooks,
            requiredCaps = requiredCaps,
        )
    }

    @Test
    fun `a receiver without the capability is cancelled before any frame is written`() = runBlocking<Unit> {
        val received = Received()
        val target = receiver(ReceiverCaps.BASE, received)
        val hooks = RecordingHooks()

        val e = assertFailsWith<ReceiverCapabilityMissingException> {
            send(target, hooks, requiredCaps = setOf(ReceiverCaps.PENDING_ENVELOPE_V1))
        }

        assertEquals(ReceiverCapabilityMissingException.RECEIVER_UPDATE_REQUIRED, e.message)
        assertEquals(setOf(ReceiverCaps.PENDING_ENVELOPE_V1), e.missing)
        assertEquals(listOf<String?>(CancelState.STOPPED), hooks.abortAnswers, "the receiver's session was cancelled by name")
        assertFalse(hooks.finalized, "no commit marker: the writer never ran")
        assertFalse(received.bodyOpened, "no upload body was ever opened")
        assertTrue(received.records.isEmpty())
    }

    @Test
    fun `a receiver advertising the capability gets the container`() = runBlocking<Unit> {
        val received = Received()
        val target = receiver(P2pContainerFusion.RESTORE_RECEIVER_CAPS, received)
        val hooks = RecordingHooks()

        val ok = send(target, hooks, requiredCaps = setOf(ReceiverCaps.PENDING_ENVELOPE_V1))

        assertTrue(ok)
        withTimeout(10_000) { while (received.records.size < 3) kotlinx.coroutines.delay(10) }
        assertTrue(hooks.finalized)
        assertTrue(hooks.abortAnswers.isEmpty())
    }

    @Test
    fun `the real-import receiver advertises the capability next to status and beat`() {
        assertEquals(
            listOf(ReceiverCaps.STATUS_V1, ReceiverCaps.BEAT_V1, ReceiverCaps.PENDING_ENVELOPE_V1, ReceiverCaps.VERDICT_RETAIN_V1),
            P2pContainerFusion.RESTORE_RECEIVER_CAPS,
        )
    }
}
