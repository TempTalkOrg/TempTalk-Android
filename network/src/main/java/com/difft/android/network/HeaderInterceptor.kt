package com.difft.android.network

import android.text.TextUtils
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.network.config.UserAgentManager
import okhttp3.Interceptor
import okhttp3.Response

class HeaderInterceptor(private val authProvider: ChativeHttpClient.AuthProvider?) : Interceptor {
    companion object {
        const val HEADER_KEY_AUTHORIZATION = "Authorization"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request
            .newBuilder()
            .addHeader("User-Agent", UserAgentManager.getUserAgent())
            .addHeader("Accept-Language", LanguageUtils.getLanguage(com.difft.android.base.utils.application).language)

        if (request.headers[HEADER_KEY_AUTHORIZATION] == null) { // only add header to the requests that not has been set before.
            val auth = authProvider?.provideAuth()
            if (!TextUtils.isEmpty(auth)) {
                builder.addHeader(HEADER_KEY_AUTHORIZATION, auth!!)
            }
        }

        val newRequest = builder.build()
        return chain.proceed(newRequest)
    }
}