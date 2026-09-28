package com.difft.android.network.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.GET

/**
 * Retrofit interfaces whose only purpose is to carry [Auth] declarations, plus [requestOf], which
 * builds a REAL Retrofit request — so the `Invocation` tag the resolver reads is produced the way
 * production produces it rather than hand-assembled.
 */
@Auth(AuthStyle.BASIC)
internal interface BasicDeclaringService {
    @GET("basic")
    fun inheritsInterface(): Call<ResponseBody>

    @Auth(AuthStyle.JWT)
    @GET("jwt")
    fun overridesWithJwt(): Call<ResponseBody>
}

internal interface UndeclaredService {
    @GET("none")
    fun undeclared(): Call<ResponseBody>
}

@Auth(AuthStyle.NONE)
internal interface NoAuthService {
    @GET("strip")
    fun strip(): Call<ResponseBody>
}

@Auth(AuthStyle.JWT)
internal interface JwtService {
    @GET("token")
    fun token(): Call<ResponseBody>
}

@Auth(AuthStyle.EXPLICIT)
internal interface ExplicitService {
    @GET("explicit")
    fun explicit(): Call<ResponseBody>
}

private val retrofit: Retrofit by lazy {
    Retrofit.Builder()
        .baseUrl(BASE_URL)
        // Never executed: only Call.request() is read, which Retrofit builds without any I/O.
        .client(OkHttpClient.Builder().build())
        .build()
}

internal const val BASE_URL = "https://auth.test.invalid/"

/** The `Request` Retrofit would send for [call], complete with its `Invocation` tag. */
internal fun <T> requestOf(service: Class<T>, call: (T) -> Call<*>): Request =
    call(retrofit.create(service)).request()
