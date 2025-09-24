package com.difft.android.network

import okhttp3.Interceptor
import okhttp3.Response

class NoHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
            .newBuilder()
            .removeHeader("authorization")
            .removeHeader("content-type")
            .build()
        return chain.proceed(request);
    }
}