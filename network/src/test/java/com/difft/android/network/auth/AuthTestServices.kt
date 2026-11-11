package com.difft.android.network.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.GET






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
        
        .client(OkHttpClient.Builder().build())
        .build()
}

internal const val BASE_URL = "https://auth.test.invalid/"


internal fun <T> requestOf(service: Class<T>, call: (T) -> Call<*>): Request =
    call(retrofit.create(service)).request()
