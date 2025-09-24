package com.difft.android.websocket.internal

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

class TrustAllSSLSocketFactory {
    companion object {
        @JvmStatic
        fun create(manager: X509TrustManager): SSLSocketFactory = runCatching {
            val ctx = SSLContext.getInstance("TLSv1.2")
            ctx.init(null, arrayOf(manager), SecureRandom())
            ctx.socketFactory
        }.getOrThrow()
    }


    class TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {
        }

        override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    }
}