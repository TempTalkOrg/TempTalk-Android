package com.difft.android.network.ca

import android.content.Context
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class OfficialSSLSocketFactoryCreator(context: Context) {

    private val ca by lazy {
        val fileNameCA = "chative_ssl_ca.pem"
        context.assets.open(fileNameCA).buffered().use { inputStreamCA ->
            val certificateFactory = CertificateFactory.getInstance("X.509")
            certificateFactory.generateCertificate(inputStreamCA) as X509Certificate
        }
    }

    private val trustManagerFactory by lazy {
        val defaultType = KeyStore.getDefaultType()
        val keyStore = KeyStore.getInstance(defaultType)
        keyStore.load(null, null)
        keyStore.setCertificateEntry("ca", ca)

        val defaultAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
        val trustManagerFactory = TrustManagerFactory.getInstance(defaultAlgorithm)
        trustManagerFactory.init(keyStore)

        trustManagerFactory
    }

    val trustManager by lazy {
        val trustManagers = trustManagerFactory.trustManagers
        trustManagers.first() as X509TrustManager
    }

    val socketFactory by lazy {
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, trustManagerFactory.trustManagers, null)
        sslContext.socketFactory
    }
}