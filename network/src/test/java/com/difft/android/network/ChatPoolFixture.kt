package com.difft.android.network

import com.difft.android.base.user.Data
import com.difft.android.base.user.Domain
import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.base.user.Service
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.proxy.ProxyConfigProvider
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import com.difft.android.network.speedtest.DomainSpeedTester
import io.mockk.every
import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * A fake chat pool for failover tests (#1396): one [MockWebServer] per virtual host name, reached
 * through a fake [Dns] and a [SocketFactory] that sends every connect for host `x` to `x`'s server,
 * so the production hosts (`https://host/…`, port 443) can be used as they are.
 *
 * It also wires the REAL [UrlManager] and [DomainSpeedTestCoordinator] over a mocked config, so the
 * failover tests exercise host selection, marks and exhaustion end to end. The clock, the network
 * state and the probe launcher of the coordinator are fakes ([online], [probeLaunches]).
 *
 * [client] builds the production interceptor order: routing, optional normalization, failover, and
 * the exchange observer as a network interceptor.
 *
 * @param poolHosts the config chat pool, in order; the first is the startup host.
 * @param otherHosts hosts with a server that are not in the pool (a CDN, a redirect target).
 * @param tls serve and request over HTTPS, with hosts in [untrustedHosts] presenting a certificate
 * the client does not trust.
 * @param tunnelHosts the `proxy.tunnelDomains.chat` set; the proxy is on when non-null.
 */
internal class ChatPoolFixture(
    val poolHosts: List<String>,
    otherHosts: List<String> = emptyList(),
    private val tls: Boolean = false,
    untrustedHosts: Set<String> = emptySet(),
    tunnelHosts: List<String>? = null,
) : AutoCloseable {

    val servers: Map<String, MockWebServer>

    @Volatile
    var online = true

    @Volatile
    var probeLaunches = 0

    private val unresolvable = ConcurrentHashMap.newKeySet<String>()
    private val refusing = ConcurrentHashMap.newKeySet<String>()
    private val trustedRoot = HeldCertificate.Builder().certificateAuthority(0).commonName("trusted root").build()
    private val clientCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(trustedRoot.certificate)
        .build()

    private class InMemoryUserManager : UserManager {
        private var data: UserData? = UserData()
        override fun setUserData(userData: UserData, commit: Boolean) {
            data = userData
        }

        override fun getUserData(): UserData? = data
    }

    val coordinator: DomainSpeedTestCoordinator
    val urlManager: UrlManager

    init {
        val untrustedRoot = HeldCertificate.Builder().certificateAuthority(0).commonName("untrusted root").build()
        servers = (poolHosts + otherHosts).associateWith { host ->
            MockWebServer().apply {
                protocols = listOf(Protocol.HTTP_1_1)
                if (tls) {
                    val root = if (host in untrustedHosts) untrustedRoot else trustedRoot
                    val certificate = HeldCertificate.Builder().addSubjectAlternativeName(host).signedBy(root).build()
                    useHttps(
                        HandshakeCertificates.Builder()
                            .heldCertificate(certificate, root.certificate)
                            .build()
                            .sslSocketFactory()
                    )
                }
                start()
            }
        }

        val labels = poolHosts.indices.map { "chat${it + 1}" }
        val globalConfigs = mockk<GlobalConfigsManager>(relaxed = true)
        every { globalConfigs.getNewGlobalConfigs() } returns NewGlobalConfig(
            data = Data(
                domains = poolHosts.mapIndexed { i, host -> Domain(label = labels[i], domain = host, certType = "self") },
                services = listOf(Service(name = "chat", path = "/chat", domains = labels)),
            )
        )
        val proxy = mockk<ProxyConfigProvider>(relaxed = true)
        every { proxy.isEnabled } returns (tunnelHosts != null)
        every { globalConfigs.getProxyTunnelChatDomains() } returns tunnelHosts.orEmpty()

        coordinator = DomainSpeedTestCoordinator(
            globalConfigsManager = dagger.Lazy { globalConfigs },
            speedTester = mockk<DomainSpeedTester>(relaxed = true),
            userManager = InMemoryUserManager(),
            proxyConfigProvider = dagger.Lazy { proxy },
            networkAvailable = { online },
            clock = System::currentTimeMillis,
            launchProbe = { probeLaunches++ },
        )

        val environment = mockk<EnvironmentHelper>(relaxed = true)
        every { environment.ENVIRONMENT_ONLINE } returns "online"
        every { environment.ENVIRONMENT_DEVELOPMENT } returns "dev"
        every { environment.isThatEnvironment("online") } returns true
        every { environment.isThatEnvironment("dev") } returns false
        urlManager = UrlManager(
            environmentHelper = environment,
            globalConfigsManager = dagger.Lazy { globalConfigs },
            coordinator = dagger.Lazy { coordinator },
            proxyConfigProvider = dagger.Lazy { proxy },
        )
    }

    private val scheme = if (tls) "https" else "http"
    private val clients = mutableListOf<OkHttpClient>()

    /** Makes DNS fail for [host]: a connect-stage failure (nothing reaches any server). */
    fun failDns(host: String) {
        unresolvable += host
    }

    /** Makes every new TCP connect to [host] fail with a [ConnectException]; pooled connections are untouched. */
    fun refuseConnections(host: String) {
        refusing += host
    }

    fun server(host: String): MockWebServer = servers.getValue(host)

    fun requestCount(host: String): Int = server(host).requestCount

    fun ok(host: String, body: String = "ok") {
        server(host).enqueue(MockResponse.Builder().code(200).body(body).build())
    }

    fun respond(host: String, code: Int, body: String = "") {
        server(host).enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    /** Hosts the coordinator would still offer for a retry, best first. */
    fun eligibleHosts(): List<String> = coordinator.getAllHostsRanked()

    /** The startup origin of a chat client: `scheme://firstHost/chat/`. */
    val origin: String get() = "$scheme://${poolHosts.first()}/chat/"

    /** A request to [host] (default: the startup host); [routed] tags it as the routing interceptor would. */
    fun request(path: String = "/chat/v1/thing", host: String = poolHosts.first(), routed: Boolean = false): Request =
        Request.Builder().url("$scheme://$host$path").apply {
            if (routed) tag(RoutedHost::class.java, RoutedHost(host, ChatService.CHAT))
        }.build()

    /**
     * A client with the production interceptor order. [routing] false leaves routing out, for tests
     * that tag the request themselves; [normalize] false leaves out [HttpClientInterceptor] to see
     * raw exceptions and responses; [resend5xx] false is the Signal API client.
     */
    fun client(
        routing: Boolean = true,
        normalize: Boolean = true,
        resend5xx: Boolean = true,
        pinned: Boolean = false,
        totalBoundMs: Long = 30_000L,
        readTimeoutMs: Long = 1_000L,
        retryOnConnectionFailure: Boolean = false,
        configure: OkHttpClient.Builder.() -> Unit = {},
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // The virtual hosts only exist behind the fake DNS: a developer machine's system proxy
            // must not intercept them.
            .proxy(Proxy.NO_PROXY)
            .dns(fakeDns)
            .socketFactory(redirectingSocketFactory)
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(retryOnConnectionFailure)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        if (tls) builder.sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        if (routing) {
            builder.addInterceptor(ChatPoolRoutingInterceptor(origin.toHttpUrl(), ChatService.CHAT) { urlManager })
        }
        if (normalize) builder.addInterceptor(HttpClientInterceptor(pinnedConnection = pinned))
        builder.addInterceptor(
            HostFailoverInterceptor(
                resendOnServerError = resend5xx,
                pinnedConnection = pinned,
                totalBoundMs = totalBoundMs,
                urlManagerProvider = { urlManager },
            )
        )
        builder.addNetworkInterceptor(ExchangeObserver())
        builder.configure()
        return builder.build().also { clients += it }
    }

    private val fakeDns = Dns { name ->
        if (name in unresolvable || name !in servers) throw UnknownHostException(name)
        listOf(InetAddress.getByAddress(name, byteArrayOf(127, 0, 0, 1)))
    }

    private val redirectingSocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val address = endpoint as InetSocketAddress
                if (address.hostString in refusing) throw ConnectException("Failed to connect to ${address.hostString}")
                val server = servers[address.hostString]
                    ?: throw ConnectException("no server for ${address.hostString}")
                super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.port), timeout)
            }
        }

        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()
    }

    override fun close() {
        clients.forEach {
            it.dispatcher.executorService.shutdown()
            it.connectionPool.evictAll()
        }
        servers.values.forEach { runCatching { it.close() } }
    }
}
