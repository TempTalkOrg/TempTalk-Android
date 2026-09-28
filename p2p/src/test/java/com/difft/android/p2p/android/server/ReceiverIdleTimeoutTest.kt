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
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * A peer that stops sending without closing the socket must not be able to park a connection
 * forever. This is the shape a suspended iOS sender takes — the OS freezes the process on
 * backgrounding, so the socket stays open and simply goes quiet — and before the idle deadline the
 * receiver's body read blocked indefinitely, leaving the migration UI on a progress bar that could
 * never move again.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiverIdleTimeoutTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private var server: ReceiverHttpServer? = null

    @After fun tearDown() { server?.stop() }

    @Test
    fun `a peer that opens a connection and goes silent is dropped, not waited on forever`() {
        val error = AtomicReference<String>()
        val errored = CountDownLatch(1)
        val cert = SelfSignedCert.loadOrCreate(CertStorage(tempFolder.newFolder("cert")), "127.0.0.1")
        val port = start(cert) { event ->
            if (event is ReceiverHttpServer.ServerEvent.Error) {
                error.set(event.detail)
                errored.countDown()
            }
        }

        // Connect, send a partial request line, then hold the socket open and say nothing more.
        trustAllSocket(port).use { socket ->
            socket.getOutputStream().apply {
                write("POST /api/localsend/v2/upload?sessionId=s&fileId=f&token=t HTTP/1.1\r\n".toByteArray())
                flush()
            }
            assertTrue(
                "receiver still blocked on a silent peer after the idle deadline",
                errored.await(10, TimeUnit.SECONDS),
            )
        }

        assertTrue("expected a timeout, got: ${error.get()}", error.get().orEmpty().contains("timed out", ignoreCase = true))
    }

    private fun start(cert: SelfSignedCert, onEvent: (ReceiverHttpServer.ServerEvent) -> Unit): Int {
        val srv = ReceiverHttpServer(
            cert = cert,
            deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
            sessionManager = SessionManager(),
            pinPolicy = PinPolicy(),
            fileSink = CapturingFileSink(),
            requirePin = false,
            onEvent = onEvent,
            // Short enough to keep the test quick; the production value is the same clock.
            idleReadTimeoutMs = 300,
        )
        server = srv
        return srv.start(0)
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
