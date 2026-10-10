package com.difft.android.p2p.android.server

import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.io.FileSink
import com.difft.android.p2p.engine.io.TransferCommittedUnconfirmed
import com.difft.android.p2p.engine.io.TransferStorageFault
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.TransferStages
import com.difft.android.p2p.engine.model.TransferTerminal
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.blackholeSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * T40-T41c: how the two typed receive outcomes reach the wire.
 *
 * A storage fault is answered with a real `403 insufficient-storage` on the still-open connection —
 * from the finalize step and, just as importantly, from mid-body, where a bare rethrow would leave
 * the sender with a connection reset and no reason. A committed-but-unconfirmed import is answered
 * with nothing at all, and — through the linger teardown and a later receiver on the same port — it
 * must never acquire a terminal, because neither "succeeded" nor "failed" would be true.
 */
@RunWith(RobolectricTestRunner::class)
class StorageAndCommitOutcomeRouteTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private var server: ReceiverHttpServer? = null

    @After fun tearDown() { server?.stop() }

    // ── T40 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a storage fault at finalize is a 403 insufficient-storage and fails the session`() {
        val sessions = SessionManager()
        val port = start(sessions, FinalizeFailingSink(StorageFault("no room for the import")))
        val prepared = prepare(sessions)

        val reply = upload(port, prepared.first, prepared.second, body = ByteArray(1024))

        assertEquals(403, reply.status)
        assertTrue("body carries the machine-readable reason: ${reply.body}", reply.body.contains("insufficient-storage"))
        assertEquals(TransferTerminal.FAILED, sessions.status(prepared.first, prepared.third).terminal)
    }

    // ── T40b ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a storage fault mid-body is a 403 too, not a bare connection reset`() {
        val sessions = SessionManager()
        val port = start(sessions, BodyFailingSink(StorageFault("no room while the body streamed")))
        val prepared = prepare(sessions)

        // Large enough that the pump's buffer flushes into the failing sink while the sender is still
        // writing, which is the case a verbatim rethrow would turn into a reset with no reason.
        val reply = upload(port, prepared.first, prepared.second, body = ByteArray(BIG_BODY))

        assertEquals(403, reply.status)
        assertTrue("body carries the machine-readable reason: ${reply.body}", reply.body.contains("insufficient-storage"))
    }

    // ── T41 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a committed-but-unconfirmed import answers nothing and publishes no terminal`() {
        val sessions = SessionManager()
        val port = start(sessions, FinalizeFailingSink(Unconfirmed("tail not proven")))
        val prepared = prepare(sessions)

        val reply = upload(port, prepared.first, prepared.second, body = ByteArray(1024))

        assertNull("no status line may be written at all: ${reply.body}", reply.status)
        assertNull("and no terminal is published", sessions.status(prepared.first, prepared.third).terminal)
    }

    // ── T41b ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the listener teardown does not retire an unconfirmed session as cancelled`() {
        val records = tempFolder.newFolder("records-linger")
        val sessions = SessionManager(terminalStore = FileTerminalRecordStore(records))
        val port = start(sessions, FinalizeFailingSink(Unconfirmed("tail not proven")))
        val prepared = prepare(sessions)
        upload(port, prepared.first, prepared.second, body = ByteArray(1024))

        // What ReceiverLinger does once its window expires: stop the server, which clears the session.
        server?.stop()
        server = null

        val after = sessions.status(prepared.first, prepared.third)
        assertNull("a definite `cancelled` here would be a false verdict minutes after the promote", after.terminal)
        assertEquals(TransferStages.PROMOTING, after.stage)
    }

    // ── T41c ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a fresh receiver on the same port reloads the unconfirmed record without a terminal`() {
        val records = tempFolder.newFolder("records-reload")
        val sessions = SessionManager(terminalStore = FileTerminalRecordStore(records))
        val port = start(sessions, FinalizeFailingSink(Unconfirmed("tail not proven")))
        val prepared = prepare(sessions)
        upload(port, prepared.first, prepared.second, body = ByteArray(1024))
        server?.stop()
        server = null

        // A later receiver reads the same on-disk store. A missing `terminal` key must decode as null
        // rather than dropping the whole file, or /status degrades to the 404 this mechanism rejects.
        val reloaded = SessionManager(terminalStore = FileTerminalRecordStore(records))
            .status(prepared.first, prepared.third)

        assertNull(reloaded.terminal)
        assertEquals(TransferStages.PROMOTING, reloaded.stage)
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    private class StorageFault(message: String) : IOException(message), TransferStorageFault

    private class Unconfirmed(message: String) : IOException(message), TransferCommittedUnconfirmed

    /** Accepts the whole body, then fails the verdict with [failure]. */
    private class FinalizeFailingSink(private val failure: Throwable) : FileSink {
        override fun openWrite(meta: FileMetadata): Sink = blackholeSink()
        override fun finalizeFile(meta: FileMetadata, expectedSha256: String?): Result<String> =
            Result.failure(failure)
    }

    /** Fails the first real append, while the sender is still writing. */
    private class BodyFailingSink(private val failure: Throwable) : FileSink {
        override fun openWrite(meta: FileMetadata): Sink = object : Sink {
            override fun write(source: Buffer, byteCount: Long): Unit = throw failure
            override fun flush() = Unit
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }

        override fun finalizeFile(meta: FileMetadata, expectedSha256: String?): Result<String> =
            Result.success("unreachable")
    }

    private fun start(sessions: SessionManager, sink: FileSink): Int {
        val cert = SelfSignedCert.loadOrCreate(CertStorage(tempFolder.newFolder("cert-${System.nanoTime()}")), "127.0.0.1")
        val srv = ReceiverHttpServer(
            cert = cert,
            deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
            sessionManager = sessions,
            pinPolicy = PinPolicy(),
            fileSink = sink,
            requirePin = false,
            onEvent = {},
            // No interim beat may interleave with the response these cases read off the socket.
            verdictHeartbeatIntervalMs = 60_000,
        )
        server = srv
        return srv.start(0)
    }

    /** @return (sessionId, fileToken, sessionToken) for a single-file session bound to loopback. */
    private fun prepare(sessions: SessionManager): Triple<String, String, String> {
        val meta = FileMetadata(id = FILE_ID, fileName = "c.ttmig", size = 0, fileType = "application/octet-stream")
        val prepared = sessions.createSession("127.0.0.1", mapOf(FILE_ID to meta))
        return Triple(prepared.sessionId, prepared.slots.getValue(FILE_ID).token, prepared.sessionToken)
    }

    /** A null [Reply.status] means the server wrote no status line before closing. */
    private class Reply(val status: Int?, val body: String)

    private fun upload(port: Int, sessionId: String, fileToken: String, body: ByteArray): Reply =
        trustAllSocket(port).use { socket ->
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            out.write(
                (
                    "POST /api/localsend/v2/upload?sessionId=$sessionId&fileId=$FILE_ID&token=$fileToken HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
            )
            // A receiver that answers 403 mid-body closes the connection under this write.
            runCatching {
                out.write(body)
                out.flush()
            }
            val text = runCatching { socket.getInputStream().bufferedReader().readText() }.getOrDefault("")
            if (text.startsWith("HTTP/1.1 ")) {
                Reply(text.substringAfter("HTTP/1.1 ").take(3).toInt(), text.substringAfter("\r\n\r\n", ""))
            } else {
                Reply(null, text)
            }
        }

    /** The pinning is not the subject here — accept whatever cert the server presents. */
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

        /** Beyond the pump's buffer, so the failing sink is reached while the sender is still writing. */
        const val BIG_BODY = 256 * 1024
    }
}
