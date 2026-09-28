package com.difft.android.p2p.android.client

import com.difft.android.p2p.android.server.ReceiverHttpServer
import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.CapturingFileSink
import com.difft.android.p2p.engine.model.CancelState
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.model.TransferStages
import com.difft.android.p2p.engine.model.TransferTerminal
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sender's `/status` and `/cancel` against a real receiver: a live session reads back with its
 * progress, a finished one with its terminal, an unknown one (or a wrong token) as null, and the
 * cancel answers with what it did instead of a bare 200.
 */
@RunWith(RobolectricTestRunner::class)
class SenderHttpClientStatusTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private var server: ReceiverHttpServer? = null

    @After fun tearDown() { server?.stop() }

    @Test
    fun `status and cancel round-trip through the receiver`() = runTest {
        val cert = SelfSignedCert.loadOrCreate(CertStorage(tempFolder.newFolder("cert")), "127.0.0.1")
        val sessions = SessionManager()
        val srv = ReceiverHttpServer(
            cert = cert,
            deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
            sessionManager = sessions,
            pinPolicy = PinPolicy(),
            fileSink = CapturingFileSink(),
            requirePin = false,
            onEvent = {},
        )
        server = srv
        val port = srv.start(0)
        val client = SenderHttpClient(QrPayload(ip = "127.0.0.1", port = port, fp = cert.sha256Fingerprint, pin = "x", alias = "rx"))
        val prepared = sessions.createSession(
            "127.0.0.1",
            mapOf("f" to FileMetadata(id = "f", fileName = "x", size = 1, fileType = "application/octet-stream")),
        )
        val id = prepared.sessionId
        val token = prepared.sessionToken

        sessions.stage(TransferStages.IMPORTING)
        sessions.recordsImported(7)
        val live = client.status(id, token)!!
        assertEquals(TransferStages.IMPORTING, live.stage)
        assertEquals(7L, live.records)
        assertNull(live.terminal)

        assertNull(client.status(id, "wrong-token"))
        assertNull(client.status("no-such-session", token))

        // Past the commit point the cancel is too late and the session stays live.
        sessions.committing()
        assertEquals(CancelState.TOO_LATE, client.cancel(id, token))
        assertTrue(client.status(id, token)!!.terminal == null)

        sessions.markTerminal(id, TransferTerminal.SUCCEEDED)
        assertEquals(TransferTerminal.SUCCEEDED, client.status(id, token)!!.terminal)
        // A cancel for a finished session is answered too late as well; an unknown one gives no state.
        assertEquals(CancelState.TOO_LATE, client.cancel(id, token))
        assertNull(client.cancel("no-such-session", token))
    }
}
