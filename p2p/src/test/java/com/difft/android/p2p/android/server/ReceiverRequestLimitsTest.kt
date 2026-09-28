package com.difft.android.p2p.android.server

import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.CapturingFileSink
import com.difft.android.p2p.engine.model.DeviceInfo
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
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The pairing window is open to anyone who accepts our certificate, so a control request must be
 * bounded before its body is read: an unbounded `Content-Length` would otherwise buy a large heap
 * allocation from an unauthenticated caller. A same-device receiver additionally takes itself off
 * the LAN — it advertises loopback alone, so that is all it listens on.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiverRequestLimitsTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val servers = CopyOnWriteArrayList<ReceiverHttpServer>()

    @After fun tearDown() { servers.forEach { it.stop() } }

    @Test
    fun `an oversized control body is refused unread and the receiver keeps serving`() {
        val port = newServer().start(0)

        val refused = send(
            port,
            "POST /api/localsend/v2/register HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\nContent-Length: 104857600\r\nConnection: close\r\n\r\ntiny",
        )
        assertTrue("oversized register was not refused: $refused", refused.startsWith("HTTP/1.1 400"))

        val info = send(
            port,
            "GET /api/localsend/v2/info HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        )
        assertTrue("receiver stopped serving after the refusal: $info", info.startsWith("HTTP/1.1 200"))
    }

    @Test
    fun `a loopback bind listens only on loopback, the default keeps the wildcard`() {
        val loopback = newServer()
        // The literal the pairing payload advertises, not getLoopbackAddress(): that is ::1 on Android,
        // which a peer dialling 127.0.0.1 cannot reach.
        loopback.start(0, InetAddress.getByName("127.0.0.1"))
        assertTrue("same-device receiver reachable off loopback", loopback.boundAddress!!.isLoopbackAddress)
        assertEquals("127.0.0.1", loopback.boundAddress!!.hostAddress)

        val wildcard = newServer()
        wildcard.start(0)
        assertTrue("cross-device receiver lost its LAN bind", wildcard.boundAddress!!.isAnyLocalAddress)
    }

    private fun newServer(): ReceiverHttpServer {
        val cert = SelfSignedCert.loadOrCreate(CertStorage(tempFolder.newFolder()), "127.0.0.1")
        return ReceiverHttpServer(
            cert = cert,
            deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
            sessionManager = SessionManager(),
            pinPolicy = PinPolicy(),
            fileSink = CapturingFileSink(),
            requirePin = false,
        ).also { servers += it }
    }

    /** Sends [request] verbatim and returns the status line. */
    private fun send(port: Int, request: String): String = trustAllSocket(port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().apply { write(request.toByteArray()); flush() }
        socket.getInputStream().bufferedReader().readLine().orEmpty()
    }

    /** The certificate is not the subject here — accept whatever the server presents. */
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
