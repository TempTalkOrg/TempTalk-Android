package com.difft.android.network.proxy

import com.difft.android.base.log.lumberjack.L
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/**
 * Probes whether a [ProxyConfig] points at a live, correctly-pinned proxy.
 *
 * It performs exactly the OUTER hop of [TlsTunnelSocket]: TCP-connects to
 * `host:port` and runs the outer TLS handshake guarded by [ProxyPinTrustManager].
 * A successful handshake proves three things the user just configured:
 *  - the proxy host/port is reachable (right IP/port, proxy is up, not blocked),
 *  - it speaks TLS,
 *  - its leaf cert matches the pinned SPKI fingerprint (`fp` correct).
 *
 * It intentionally does NOT drive an end-to-end request through the relay to a
 * TempTalk origin (which would couple to flavor-specific hosts); the outer hop
 * is the part the user directly configures and the most common failure point.
 *
 * Blocking + socket-timeout bounded; call from a background dispatcher.
 */
object ProxyConnectivityChecker {

    enum class Failure { NONE, UNREACHABLE, TIMEOUT, PIN_MISMATCH, UNKNOWN }

    data class Outcome(val ok: Boolean, val failure: Failure = Failure.NONE)

    fun check(config: ProxyConfig, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Outcome {
        var raw: Socket? = null
        var ssl: SSLSocket? = null
        return try {
            raw = Socket()
            raw.connect(InetSocketAddress(config.host, config.port), timeoutMs)

            val sni = config.outerSni()
            val factory = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(ProxyPinTrustManager(config.spkiPinBase64)), RNG)
            }.socketFactory
            ssl = (factory.createSocket(raw, sni, config.port, true) as SSLSocket).apply {
                soTimeout = timeoutMs
                runCatching {
                    sslParameters = sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
                }
            }
            ssl.startHandshake() // triggers the SPKI pin check
            L.i { "[Proxy] connectivity check OK" }
            Outcome(true)
        } catch (e: SSLHandshakeException) {
            // Pin mismatch surfaces as a handshake failure (CertificateException wrapped).
            L.w { "[Proxy] connectivity check handshake failed: ${e.message}" }
            Outcome(false, Failure.PIN_MISMATCH)
        } catch (e: SocketTimeoutException) {
            Outcome(false, Failure.TIMEOUT)
        } catch (e: UnknownHostException) {
            Outcome(false, Failure.UNREACHABLE)
        } catch (e: ConnectException) {
            Outcome(false, Failure.UNREACHABLE)
        } catch (e: Exception) {
            L.w { "[Proxy] connectivity check error: ${e.message}" }
            Outcome(false, Failure.UNKNOWN)
        } finally {
            runCatching { ssl?.close() }
            runCatching { raw?.close() }
        }
    }

    private const val DEFAULT_TIMEOUT_MS = 8000

    // Shared across checks — SecureRandom is thread-safe; per-call seeding is avoidable.
    private val RNG = SecureRandom()
}
