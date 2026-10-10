package com.difft.android.migration.v2.transfer

import com.difft.android.p2p.android.server.ReceiverHttpServer
import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T42 — the regression the spool exists for: an importer far slower than the link must no longer
 * back-pressure the socket. Before the spool the body went into a 1 MiB `okio.Pipe`, so a staging
 * commit or an attachment fsync stalled the sender's writes and tripped its write-stall watchdog on
 * a transfer that was otherwise healthy.
 *
 * Run against the real [ReceiverHttpServer] over loopback TLS, with a consumer deliberately slower
 * than the sender. The design's figures (64 MiB at 5 s per MiB) are scaled down — the property is a
 * ratio, not a size — so the case runs in seconds while still making the consumer several times
 * slower than the writer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class ReceiveSpoolBackpressureTest {

    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: ReceiverHttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
        scope.cancel()
    }

    @Test
    fun `a slow importer does not stall the sender's writes`() {
        val consumed = java.util.concurrent.atomic.AtomicLong()
        val sink = P2pContainerReceiveSink<Long>(
            scope = scope,
            spoolDir = tmp.newFolder("spool"),
        ) { input ->
            // Several times slower than the socket: the whole point is that this never reaches it.
            val block = ByteArray(CHUNK)
            var total = 0L
            while (true) {
                val n = input.read(block)
                if (n < 0) break
                total += n
                consumed.addAndGet(n.toLong())
                Thread.sleep(CONSUMER_DELAY_MS)
            }
            total
        }

        val sessions = SessionManager()
        val cert = SelfSignedCert.loadOrCreate(CertStorage(tmp.newFolder("cert")), "127.0.0.1")
        val srv = ReceiverHttpServer(
            cert = cert,
            deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
            sessionManager = sessions,
            pinPolicy = PinPolicy(),
            fileSink = sink,
            requirePin = false,
            onEvent = {},
        )
        server = srv
        val port = srv.start(0)

        val meta = FileMetadata(id = FILE_ID, fileName = "c.ttmig", size = BODY_BYTES.toLong(), fileType = "application/octet-stream")
        val prepared = sessions.createSession("127.0.0.1", mapOf(FILE_ID to meta))
        val fileToken = prepared.slots.getValue(FILE_ID).token

        var slowestWriteMs = 0L
        val chunk = ByteArray(CHUNK) { it.toByte() }
        val started = System.nanoTime()
        val response = trustAllSocket(port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val out = socket.getOutputStream()
            out.write(
                (
                    "POST /api/localsend/v2/upload?sessionId=${prepared.sessionId}&fileId=$FILE_ID&token=$fileToken HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\nContent-Length: $BODY_BYTES\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
            )
            repeat(BODY_BYTES / CHUNK) {
                val at = System.nanoTime()
                out.write(chunk)
                out.flush()
                slowestWriteMs = maxOf(slowestWriteMs, (System.nanoTime() - at) / 1_000_000)
            }
            val writeElapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(
                writeElapsedMs < CONSUMER_TOTAL_MS / 2,
                "the sender finished writing in ${writeElapsedMs}ms; the importer needs at least ${CONSUMER_TOTAL_MS}ms, " +
                    "so anything close to that means the socket was waiting on the import",
            )
            socket.getInputStream().bufferedReader().readText()
        }

        assertTrue(response.startsWith("HTTP/1.1 200"), "the transfer completed with a 200: $response")
        assertEquals(BODY_BYTES.toLong(), consumed.get(), "every byte reached the importer")
        assertTrue(slowestWriteMs < MAX_WRITE_STALL_MS, "no single write stalled (worst=${slowestWriteMs}ms)")
    }

    private fun trustAllSocket(port: Int): Socket {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        return ctx.socketFactory.createSocket("127.0.0.1", port)
    }

    private companion object {
        const val FILE_ID = "container"
        const val CHUNK = 64 * 1024
        const val BODY_BYTES = 64 * CHUNK
        const val CONSUMER_DELAY_MS = 25L

        /** Lower bound on the importer's own time, against which the sender's must look negligible. */
        const val CONSUMER_TOTAL_MS = (BODY_BYTES / CHUNK) * CONSUMER_DELAY_MS

        const val MAX_WRITE_STALL_MS = 1_000L
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
