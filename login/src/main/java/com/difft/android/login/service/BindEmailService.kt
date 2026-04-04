package com.difft.android.login.service

import com.difft.android.login.data.BindAccountResponse
import com.difft.android.network.BaseResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface BindEmailService {
    @POST("v2/auth/bind/send")
    suspend fun verifyEmail(
        @Header("Authorization") baseAuth: String,
        @Body body: Map<String, String>
    ): BaseResponse<BindAccountResponse>

    @POST("v2/auth/bind/verification")
    suspend fun verifyEmailCode(
        @Header("Authorization") baseAuth: String,
        @Body body: Map<String, String>
    ): BaseResponse<Any>

    @POST("v2/auth/bind/sms/send")
    suspend fun verifyPhone(
        @Header("Authorization") baseAuth: String,
        @Body body: Map<String, String>
    ): BaseResponse<BindAccountResponse>

    @POST("v2/auth/bind/sms/verification")
    suspend fun verifyPhoneCode(
        @Header("Authorization") baseAuth: String,
        @Body body: Map<String, String>
    ): BaseResponse<Any>
}
