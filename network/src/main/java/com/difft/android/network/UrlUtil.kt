package com.difft.android.network

import java.net.MalformedURLException
import java.net.URL


object UrlUtil {
    fun getBaseUrl(urlString: String?): String? {
        var baseUrl: String? = null
        try {
            val url = URL(urlString)
            val protocol = url.protocol
            val host = url.host
            val port = url.port
            baseUrl = protocol + "://" + host + (if (port < 0) "" else ":$port")
        } catch (e: MalformedURLException) {
            e.printStackTrace()
        }
        return baseUrl
    }
}