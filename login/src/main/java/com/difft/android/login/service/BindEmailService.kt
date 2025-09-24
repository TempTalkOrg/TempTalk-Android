package com.difft.android.login.service

import com.difft.android.login.data.BindAccountResponse
import com.difft.android.network.BaseResponse
import io.reactivex.rxjava3.core.Single
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface BindEmailService {
    @POST("v2/auth/bind/send")
    fun verifyEmail(
        @Header("Authorization") baseAuth: String,
        @Body body: Map<String, String>
    ): Single<BaseResponse<BindAccountResponse>>

    @POST("v2/auth/bind/verification")
    fun verifyEmailCode(
        @Header("Authorization") baseAuth: String,
        @Body body: Map<String, String>
    ): Single<BaseResponse<Any>>

    @POST("v2/auth/bind/sms/send")
    fun verifyPhone(
        @Header("Authorization") baseAuth: String,
        @Body body: Map<String, String>
    ): Single<BaseResponse<BindAccountResponse>>

    @POST("v2/auth/bind/sms/verification")
    fun verifyPhoneCode(
        @Header("Authorization") baseAuth: String,
        @Body body: Map<String, String>
    ): Single<BaseResponse<Any>>
}