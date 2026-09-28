package com.difft.android.p2p.android.server

import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.CapturingFileSink
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Cancelling a receive must end the connection the body is arriving on. Each connection is a blocking
 * socket read on an IO thread, which cancelling its coroutine cannot unblock, so before [ReceiverHttpServer.stop]
 * closed the accepted sockets a cancelled receiver went on reading the upload — the sender saw nothing
 * and kept sending until its own user cancelled too. The connection dropped by the stop must also not
 * be reported as a failure: the owner already left the transfer, and a late error would overwrite that.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiverStopClosesConnectionsTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private var server: ReceiverHttpServer? = null

    @After fun tearDown() { server?.stop() }

    @Test
    fun `stop ends a connection still being read and reports no failure for it`() {
        val errors = CopyOnWriteArrayList<String>()
        val cert = SelfSignedCert.loadOrCreate(CertStorage(tempFolder.newFolder("cert")), "127.0.0.1")
        val srv = start(cert) { event ->
            if (event is ReceiverHttpServer.ServerEvent.Error) errors += event.detail
        }
        val port = srv.start(0)

        trustAllSocket(port).use { socket ->
            socket.soTimeout = 5_000
            // A head the handler blocks on — the request never completes, the way a body still in
            // flight looks from the read side. The write returning means the handshake is done and
            // the server thread is inside the connection handler.
            socket.getOutputStream().apply {
                write("POST /api/localsend/v2/upload?sessionId=s&fileId=f&token=t HTTP/1.1\r\n".toByteArray())
                flush()
            }

            srv.stop()

            // The peer sees the connection end — EOF or a reset — rather than its read timing out.
            val ended = try {
                socket.getInputStream().read() == -1
            } catch (_: SocketTimeoutException) {
                false
            } catch (_: IOException) {
                true
            }
            assertTrue("peer still connected after stop", ended)
        }

        assertTrue("stop must not report the connection it dropped: $errors", errors.isEmpty())
    }

    private fun start(cert: SelfSignedCert, onEvent: (ReceiverHttpServer.ServerEvent) -> Unit): ReceiverHttpServer {
        val srv = ReceiverHttpServer(
            cert = cert,
            deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
            sessionManager = SessionManager(),
            pinPolicy = PinPolicy(),
            fileSink = CapturingFileSink(),
            requirePin = false,
            onEvent = onEvent,
        )
        server = srv
        return srv
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
