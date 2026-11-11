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
            ssl.startHandshake() 
            L.i { "[Proxy] connectivity check OK" }
            Outcome(true)
        } catch (e: SSLHandshakeException) {
            
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

    
    private val RNG = SecureRandom()
}
