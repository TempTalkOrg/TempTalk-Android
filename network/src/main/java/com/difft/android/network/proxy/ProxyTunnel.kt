package com.difft.android.network.proxy

import android.os.Looper
import com.difft.android.base.log.lumberjack.L
import okhttp3.Dns
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.SecureRandom
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager





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










internal class TlsTunnelSocket(
    private val config: ProxyConfig,
    private val provider: ProxyConfigProvider,
) : Socket() {
    private var delegate: Socket? = null

    
    @Volatile
    private var closed = false

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        
        
        
        val proxyAddresses = provider.resolvedProxyAddresses
        val targetAddress = (endpoint as? InetSocketAddress)?.address
        val viaProxy = targetAddress != null && proxyAddresses.contains(targetAddress)

        if (!viaProxy) {
            
            val direct = Socket()
            try {
                direct.connect(endpoint, timeout)
            } catch (e: Exception) {
                
                runCatching { direct.close() }
                throw e
            }
            delegate = direct
            return
        }

        
        
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

            
            runCatching {
                socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
            }

            socket.startHandshake() 
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
    
    override fun isClosed(): Boolean = closed || (delegate?.isClosed ?: super.isClosed())
    override fun isInputShutdown(): Boolean = delegate?.isInputShutdown ?: false
    override fun isOutputShutdown(): Boolean = delegate?.isOutputShutdown ?: false
    override fun getRemoteSocketAddress(): SocketAddress? = delegate?.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate?.localSocketAddress
    override fun setSoTimeout(timeout: Int) { delegate?.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate?.soTimeout ?: 0
    override fun setTcpNoDelay(on: Boolean) { delegate?.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate?.tcpNoDelay ?: false
    override fun setKeepAlive(on: Boolean) { delegate?.keepAlive = on }

    override fun close() {
        closed = true
        val socket = delegate ?: return super.close()
        
        
        
        if (Looper.myLooper() == Looper.getMainLooper()) {
            CLOSE_EXECUTOR.execute {
                runCatching { socket.close() }
                    .onFailure { L.w { "[Proxy] async tunnel close failed: ${it.stackTraceToString()}" } }
            }
        } else {
            socket.close()
        }
    }

    private companion object {
        
        
        private val RNG = SecureRandom()

        
        private val CLOSE_EXECUTOR: Executor = Executors.newCachedThreadPool { r ->
            Thread(r, "proxy-tunnel-close").apply { isDaemon = true }
        }

        
        
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
                
                provider.publishResolvedProxyAddresses(it.toSet())
            }
            .getOrElse {
                L.w { "[Proxy] proxy host resolve failed; failing closed (no system DNS fallback)" }
                throw java.net.UnknownHostException("[Proxy] cannot resolve proxy host")
            }
    }
}
