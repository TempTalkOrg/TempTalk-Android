package com.difft.android.websocket.api.util

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * SSLSocketFactory wrapper that restricts enabled protocols to TLS 1.2 and 1.3.
 *
 * Filters the desired protocols against the device's actual supported protocols
 * at runtime, so it works safely across all API levels (TLS 1.3 is available
 * from Android 10 / API 29; older devices fall back to TLS 1.2 only).
 *
 * @see SSLSocketFactory
 */
class TlsSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    @Throws(IOException::class)
    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        return patch(delegate.createSocket(s, host, port, autoClose))
    }

    @Throws(IOException::class, UnknownHostException::class)
    override fun createSocket(host: String?, port: Int): Socket {
        return patch(delegate.createSocket(host, port))
    }

    @Throws(IOException::class, UnknownHostException::class)
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        return patch(delegate.createSocket(host, port, localHost, localPort))
    }

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress?, port: Int): Socket {
        return patch(delegate.createSocket(host, port))
    }

    @Throws(IOException::class)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
        return patch(delegate.createSocket(address, port, localAddress, localPort))
    }

    private fun patch(s: Socket): Socket {
        if (s is SSLSocket) {
            val supported = HashSet(s.supportedProtocols.asList())
            val enabled = ArrayList<String>()
            for (protocol in TLS_V12_V13_ONLY) {
                if (supported.contains(protocol)) {
                    enabled.add(protocol)
                }
            }
            if (enabled.isNotEmpty()) {
                s.enabledProtocols = enabled.toTypedArray()
            }
        }
        return s
    }

    companion object {
        private val TLS_V12_V13_ONLY = arrayOf("TLSv1.3", "TLSv1.2")
    }
}
