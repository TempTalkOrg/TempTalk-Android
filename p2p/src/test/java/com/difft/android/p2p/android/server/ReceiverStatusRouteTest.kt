package com.difft.android.p2p.android.server

import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.CapturingFileSink
import com.difft.android.p2p.engine.model.CancelResponse
import com.difft.android.p2p.engine.model.CancelState
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.P2pJson
import com.difft.android.p2p.engine.model.StatusResponse
import com.difft.android.p2p.engine.model.TransferStages
import com.difft.android.p2p.engine.model.TransferTerminal
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager








@RunWith(RobolectricTestRunner::class)
class ReceiverStatusRouteTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private var server: ReceiverHttpServer? = null

    @After fun tearDown() { server?.stop() }

    @Test
    fun `status is token-gated, tracks progress, and keeps answering after the session ends`() {
        val cert = SelfSignedCert.loadOrCreate(CertStorage(tempFolder.newFolder("cert")), "127.0.0.1")
        val sessions = SessionManager(terminalStore = FileTerminalRecordStore(tempFolder.newFolder("records")))
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
        val prepared = sessions.createSession(
            "127.0.0.1",
            mapOf("f" to FileMetadata(id = "f", fileName = "x", size = 1, fileType = "application/octet-stream")),
        )
        val id = prepared.sessionId
        val token = prepared.sessionToken

        
        assertEquals(400, request(port, "GET", "/api/localsend/v2/status?sessionId=$id").status)
        assertEquals(404, request(port, "GET", "/api/localsend/v2/status?sessionId=$id&token=nope").status)
        assertEquals(404, request(port, "GET", "/api/localsend/v2/status?sessionId=other&token=$token").status)

        
        assertEquals(403, request(port, "POST", "/api/localsend/v2/upload?sessionId=$id&fileId=f&token=bad").status)
        assertNull(status(port, id, token).terminal)

        
        sessions.stage(TransferStages.RECEIVING)
        sessions.attachmentBytesLanded(100)
        val live = status(port, id, token)
        assertEquals(TransferStages.RECEIVING, live.stage)
        assertEquals(100L, live.attachmentBytes)
        assertNull(live.terminal)
        val revBefore = live.progressRevision
        sessions.attachmentBytesLanded(1)
        assertTrue(status(port, id, token).progressRevision > revBefore)

        
        sessions.authorizeUpload(id, "f", prepared.slots.getValue("f").token, "127.0.0.1")
        assertTrue(sessions.committing(id))
        val late = request(port, "POST", "/api/localsend/v2/cancel?sessionId=$id&token=$token")
        assertEquals(200, late.status)
        assertEquals(CancelState.TOO_LATE, P2pJson.decodeFromString<CancelResponse>(late.body).state)
        assertNull(status(port, id, token).terminal)

        
        sessions.markTerminal(id, TransferTerminal.SUCCEEDED)
        val ended = status(port, id, token)
        assertEquals(TransferTerminal.SUCCEEDED, ended.terminal)
        assertEquals(TransferStages.DONE, ended.stage)
        assertEquals(404, request(port, "GET", "/api/localsend/v2/status?sessionId=$id&token=nope").status)

        
        srv.closeToNewSessions()
        assertEquals(TransferTerminal.SUCCEEDED, status(port, id, token).terminal)
        assertEquals(409, request(port, "POST", "/api/localsend/v2/prepare-upload").status)
    }

    private fun status(port: Int, id: String, token: String): StatusResponse {
        val r = request(port, "GET", "/api/localsend/v2/status?sessionId=$id&token=$token")
        assertEquals(r.body, 200, r.status)
        return P2pJson.decodeFromString(r.body)
    }

    private class Reply(val status: Int, val body: String)

    
    private fun request(port: Int, method: String, target: String): Reply = trustAllSocket(port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().apply {
            write(("$method $target HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
            flush()
        }
        val text = socket.getInputStream().bufferedReader().readText()
        val status = text.substringAfter("HTTP/1.1 ").take(3).toInt()
        val body = text.substringAfter("\r\n\r\n", "")
        Reply(status, body)
    }

    
    private fun trustAllSocket(port: Int): Socket {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom()) }
        return ctx.socketFactory.createSocket("127.0.0.1", port)
    }
}
