package com.difft.android.network.proxy

import com.difft.android.base.log.lumberjack.L
import okhttp3.Dns
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Outer-hop trust manager for Mode B: validates ONLY that the proxy's leaf
 * certificate public key matches the pinned SPKI fingerprint. It deliberately
 * does NOT build a CA chain (the cert is self-signed), and it is used solely for
 * the client⟷proxy hop — the inner TLS to TempTalk keeps its own chative-CA
 * trust manager.
 */
internal class ProxyPinTrustManager(spkiPinBase64: String) : X509TrustManager {
    private val expectedPin = spkiPinBase64.trim()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("[Proxy] empty certificate chain")
        if (!ProxySpki.matches(leaf, expectedPin)) {
            throw CertificateException("[Proxy] SPKI pin mismatch")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * A [Socket] that decides at [connect] time between two modes, based on the IP
 * that [ProxyTunnelDns] resolved for the origin host:
 *
 * - **Tunnel** (origin is on the relay whitelist → DNS returned the proxy IP):
 *   ignores the requested endpoint, dials the proxy, performs the OUTER TLS
 *   handshake (pinned by [ProxyPinTrustManager]), and exposes that as the raw
 *   byte stream. OkHttp then wraps it with the INNER TLS whose ClientHello SNI
 *   carries the real origin host, which the relay reads via `ssl_preread`.
 *
 * - **Direct** (bypassed host → DNS returned the real origin IP): connects
 *   straight to the requested endpoint with no outer TLS, so OkHttp layers its
 *   normal (system / chative-CA) TLS on top — byte-for-byte the pre-proxy path.
 *   Used for third-party CDN bootstrap traffic (global config, version check,
 *   APK download, avatar/attachment objects) that must not enter the tunnel.
 */
internal class TlsTunnelSocket(
    private val config: ProxyConfig,
    private val provider: ProxyConfigProvider,
) : Socket() {
    private var delegate: Socket? = null

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        // Match against the union of proxy IPs ProxyTunnelDns.lookup() has resolved
        // (published on the provider) rather than re-resolving config.host here. A
        // second resolution could disagree (DNS round-robin / TTL) and flip a tunneled
        // destination to the direct branch — a fail-open that leaks the inner SNI
        // without the outer pinned TLS. The set is a union, not an overwrite, so a
        // concurrent lookup for another origin cannot evict the IP THIS route was
        // built from before we read it here. If proxy-host resolution had failed,
        // lookup() already threw UnknownHostException (fail-closed) before connect().
        val proxyAddresses = provider.resolvedProxyAddresses
        val targetAddress = (endpoint as? InetSocketAddress)?.address
        val viaProxy = targetAddress != null && proxyAddresses.contains(targetAddress)

        if (!viaProxy) {
            // Bypassed host: DNS already returned the real origin IP. Connect
            // directly; OkHttp adds its own TLS, identical to the non-proxy path.
            val direct = Socket()
            try {
                direct.connect(endpoint, timeout)
            } catch (e: Exception) {
                // Close on failure so we don't leak the FD: delegate stays null
                // otherwise and close() falls through to super.close() on the
                // empty wrapper (same guard as the tunnel handshake path below).
                runCatching { direct.close() }
                throw e
            }
            delegate = direct
            return
        }

        // Tunnel path: dial the proxy, wrap in the OUTER pinned TLS, handshake.
        // One guard closes `raw` on ANY failure (connect / createSocket / handshake)
        // — closing the underlying socket releases the FD whether or not the SSL
        // wrapper was created. Without it, `delegate` stays null and close() no-ops
        // on the empty wrapper, leaking an FD per reconnect attempt.
        val raw = Socket()
        val ssl: SSLSocket = try {
            raw.connect(InetSocketAddress(config.host, config.port), timeout)

            val sni = config.outerSni()
            val socket = outerFactory(config.spkiPinBase64)
                .createSocket(raw, sni, config.port, true) as SSLSocket

            socket.supportedProtocols
                .filter { it == "TLSv1.3" || it == "TLSv1.2" }
                .toTypedArray()
                .takeIf { it.isNotEmpty() }
                ?.let { socket.enabledProtocols = it }

            // Non-empty decoy SNI (camouflage + required for the server's 443 SNI demux).
            runCatching {
                socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
            }

            socket.startHandshake() // triggers the pin check
            socket
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
        delegate = ssl
    }

    private fun active(): Socket =
        delegate ?: throw IllegalStateException("[Proxy] tunnel not connected")

    override fun getInputStream(): InputStream = active().inputStream
    override fun getOutputStream(): OutputStream = active().outputStream
    override fun isConnected(): Boolean = delegate?.isConnected ?: false
    override fun isClosed(): Boolean = delegate?.isClosed ?: super.isClosed()
    override fun isInputShutdown(): Boolean = delegate?.isInputShutdown ?: false
    override fun isOutputShutdown(): Boolean = delegate?.isOutputShutdown ?: false
    override fun getRemoteSocketAddress(): SocketAddress? = delegate?.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate?.localSocketAddress
    override fun setSoTimeout(timeout: Int) { delegate?.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate?.soTimeout ?: 0
    override fun setTcpNoDelay(on: Boolean) { delegate?.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate?.tcpNoDelay ?: false
    override fun setKeepAlive(on: Boolean) { delegate?.keepAlive = on }
    override fun close() { delegate?.close() ?: super.close() }

    private companion object {
        // Shared across all tunnel connections — SecureRandom is thread-safe and
        // seeding from /dev/urandom per connection is avoidable.
        private val RNG = SecureRandom()

        // Cache the pinned factory: in steady state the SPKI pin is constant, so
        // rebuilding SSLContext + TrustManager on every tunnel connection is wasted
        // work. Re-init only when the pin changes (a new proxy config). The race on
        // first use is benign — two threads may each build a factory; the last write
        // wins and both are equivalent for the same pin. @Volatile gives visibility.
        @Volatile
        private var cachedFactory: Pair<String, SSLSocketFactory>? = null

        fun outerFactory(pin: String): SSLSocketFactory {
            cachedFactory?.let { (cachedPin, factory) -> if (cachedPin == pin) return factory }
            return SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(ProxyPinTrustManager(pin)), RNG)
            }.socketFactory.also { cachedFactory = pin to it }
        }
    }
}

/**
 * OkHttp [SocketFactory] that returns a [TlsTunnelSocket] when the proxy is
 * enabled, and a plain socket otherwise — so disabling the proxy restores the
 * exact default behavior. OkHttp only uses the no-arg [createSocket]; the
 * remaining overloads delegate to the platform default.
 *
 * @param forCall when true, the active config is read from
 *   [ProxyConfigProvider.currentForCall] (gated by "Protect IP address in calls")
 *   instead of [ProxyConfigProvider.current]. The call signaling client passes
 *   `true` so calls connect DIRECT when the protection toggle is off, while the
 *   IM plane (forCall=false) keeps tunneling.
 */
class ProxyTunnelSocketFactory(
    private val provider: ProxyConfigProvider,
    private val forCall: Boolean = false,
) : SocketFactory() {
    private val default = getDefault()

    override fun createSocket(): Socket =
        (if (forCall) provider.currentForCall else provider.current)
            ?.let { TlsTunnelSocket(it, provider) } ?: default.createSocket()

    override fun createSocket(host: String?, port: Int): Socket = default.createSocket(host, port)
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        default.createSocket(host, port, localHost, localPort)
    override fun createSocket(host: InetAddress?, port: Int): Socket = default.createSocket(host, port)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        default.createSocket(address, port, localAddress, localPort)
}

/**
 * When the proxy is enabled, resolves a *tunneled* origin host to the proxy
 * address so OkHttp builds a route toward it ([TlsTunnelSocket] then dials the
 * proxy and the origin host rides in the inner-TLS SNI for `ssl_preread`).
 *
 * A *bypassed* host (see [ProxyConfigProvider.shouldTunnel]) keeps system DNS
 * and its real IP, which makes [TlsTunnelSocket] connect to it directly —
 * third-party CDN bootstrap traffic never enters the tunnel and never hits the
 * relay deny. When the proxy is disabled, everything falls back to system DNS.
 *
 * If proxy-host resolution fails for a tunneled origin, this method throws — it
 * deliberately does NOT fall back to system DNS on the original hostname, which
 * would defeat the tunnel's privacy invariant.
 */
class ProxyTunnelDns(
    private val provider: ProxyConfigProvider,
    private val forCall: Boolean = false,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val config = (if (forCall) provider.currentForCall else provider.current)
            ?: return Dns.SYSTEM.lookup(hostname)
        if (!provider.shouldTunnel(hostname)) return Dns.SYSTEM.lookup(hostname)
        return runCatching { InetAddress.getAllByName(config.host).toList() }
            .onSuccess {
                // Publish this resolution so TlsTunnelSocket reuses it instead of
                // re-resolving config.host at connect time (avoids a DNS-inconsistency
                // fail-open that would leak the inner SNI without the outer pinned TLS).
                provider.publishResolvedProxyAddresses(it.toSet())
            }
            .getOrElse {
                L.w { "[Proxy] proxy host resolve failed; failing closed (no system DNS fallback)" }
                throw java.net.UnknownHostException("[Proxy] cannot resolve proxy host")
            }
    }
}
