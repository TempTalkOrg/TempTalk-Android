package com.difft.android.p2p.android.server

import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.CapturingFileSink
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * A sender that cancels names its session on `POST /cancel`. The receiver must surface that as an
 * event, so the owner can report the abort as the peer leaving rather than as a dropped link — but
 * only for the session actually in flight, and only with the session token `prepare-upload` issued:
 * without it the route answers 400, and with the wrong one — or for a session the receiver does not
 * know — 404, which changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiverCancelRouteTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private var server: ReceiverHttpServer? = null

    @After fun tearDown() { server?.stop() }

    @Test
    fun `a cancel of the active session reaches the owner, any other id or token is 404`() {
        val cancelled = CopyOnWriteArrayList<String>()
        val seen = CountDownLatch(1)
        val cert = SelfSignedCert.loadOrCreate(CertStorage(tempFolder.newFolder("cert")), "127.0.0.1")
        val sessions = SessionManager()
        val srv = ReceiverHttpServer(
            cert = cert,
            deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
            sessionManager = sessions,
            pinPolicy = PinPolicy(),
            fileSink = CapturingFileSink(),
            requirePin = false,
            onEvent = { event ->
                if (event is ReceiverHttpServer.ServerEvent.PeerCancelled) {
                    cancelled += event.sessionId
                    seen.countDown()
                }
            },
        )
        server = srv
        val port = srv.start(0)
        val prepared = sessions.createSession(
            "127.0.0.1",
            mapOf("f" to FileMetadata(id = "f", fileName = "x", size = 1, fileType = "application/octet-stream")),
        )
        val active = prepared.sessionId
        val token = prepared.sessionToken

        // No token: a protocol error, not a lenient yes.
        assertTrue(postCancel(port, active, token = null).startsWith("HTTP/1.1 400"))
        // Wrong token: refused with the same 404 an unknown id gets, nothing torn down.
        assertTrue(postCancel(port, active, token = "not-the-token").startsWith("HTTP/1.1 404"))
        assertTrue(cancelled.isEmpty())
        // A stranger's (or stale) id: 404, surfaced to nobody.
        assertTrue(postCancel(port, "s-stale", token).startsWith("HTTP/1.1 404"))
        assertTrue(cancelled.isEmpty())

        assertTrue(postCancel(port, active, token).startsWith("HTTP/1.1 200"))
        assertTrue("owner never told of the cancel", seen.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(active), cancelled)
    }

    /** Sends the cancel and returns the status line. */
    private fun postCancel(port: Int, sessionId: String, token: String?): String = trustAllSocket(port).use { socket ->
        socket.soTimeout = 5_000
        val query = "sessionId=$sessionId" + (token?.let { "&token=$it" } ?: "")
        socket.getOutputStream().apply {
            write(
                ("POST /api/localsend/v2/cancel?$query HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            flush()
        }
        socket.getInputStream().bufferedReader().readLine().orEmpty()
    }

    /** The pin is not the subject here — accept whatever cert the server presents. */
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
