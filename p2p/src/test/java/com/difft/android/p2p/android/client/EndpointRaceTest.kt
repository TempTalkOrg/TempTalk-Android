package com.difft.android.p2p.android.client

import com.difft.android.p2p.android.server.ReceiverHttpServer
import com.difft.android.p2p.android.tls.CertStorage
import com.difft.android.p2p.android.tls.FingerprintTrustManager
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.io.FileSink
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import okhttp3.tls.HeldCertificate
import okio.Sink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner






@RunWith(RobolectricTestRunner::class)
class EndpointRaceTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var cert: SelfSignedCert
    private var server: ReceiverHttpServer? = null
    private val plainSockets = mutableListOf<ServerSocket>()

    @Before
    fun setUp() {
        cert = SelfSignedCert.loadOrCreate(CertStorage(tempFolder.newFolder("cert")), "127.0.0.1")
    }

    @After
    fun tearDown() {
        server?.stop()
        plainSockets.forEach { runCatching { it.close() } }
    }

    
    private fun pinnedReceiver(): Int {
        val srv = ReceiverHttpServer(
            cert = cert,
            deviceInfo = DeviceInfo(alias = "rx", fingerprint = cert.sha256Fingerprint),
            sessionManager = SessionManager(),
            pinPolicy = PinPolicy().also { it.issue() },
            fileSink = NullSink,
            requirePin = true,
        )
        server = srv
        return srv.start(0)
    }

    
    private fun tcpOnlyListener(): Int {
        val socket = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        plainSockets += socket
        return socket.localPort
    }

    
    private fun refusedPort(): Int = ServerSocket(0).use { it.localPort }

    private fun payload(port: Int, vararg ips: String, fp: String = cert.sha256Fingerprint) =
        QrPayload(ip = ips.first(), ips = ips.toList(), port = port, fp = fp, pin = "123456", alias = "rx")

    @Test
    fun `a single address passes through without a probe`() = runBlocking {
        val target = payload(refusedPort(), "10.9.9.9")
        assertEquals(target.copy(ips = listOf("10.9.9.9")), EndpointRace.resolve(target))
    }

    @Test
    fun `the pinned receiver wins over a refused port, and the result names only the winner`() = runBlocking {
        val port = pinnedReceiver()
        val target = payload(port, "127.0.0.2", "127.0.0.1") 
        val settled = EndpointRace.resolve(target, budgetMs = 5_000)
        assertEquals("127.0.0.1", settled?.ip)
        assertEquals(listOf("127.0.0.1"), settled?.ips)
    }

    @Test
    fun `a wrong certificate never wins`() = runBlocking {
        val port = pinnedReceiver()
        val other = FingerprintTrustManager.certSha256Hex(HeldCertificate.Builder().commonName("other").build().certificate)
        assertNull(EndpointRace.race(listOf("127.0.0.1", "127.0.0.2"), port, other, budgetMs = 3_000))
    }

    @Test
    fun `a candidate that stops at TCP loses to one that completes TLS`() = runBlocking {
        val tlsPort = pinnedReceiver()
        
        
        val tcpPort = tcpOnlyListener()
        val started = System.nanoTime()
        
        assertNull(EndpointRace.race(listOf("127.0.0.1"), tcpPort, cert.sha256Fingerprint, budgetMs = 1_500))
        val onlyTcpMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("budget should have ended the race, took ${onlyTcpMs}ms", onlyTcpMs in 1_000..6_000)
        assertEquals("127.0.0.1", EndpointRace.race(listOf("127.0.0.1"), tlsPort, cert.sha256Fingerprint, budgetMs = 3_000))
    }

    
    private object NullSink : FileSink {
        override fun openWrite(meta: FileMetadata): Sink = okio.blackholeSink()
        override fun finalizeFile(meta: FileMetadata, expectedSha256: String?): Result<String> = Result.success("")
    }
}
