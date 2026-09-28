package com.difft.android.login.service

import com.difft.android.login.data.BindAccountResponse
import com.difft.android.network.BaseResponse
import com.difft.android.network.auth.Auth
import com.difft.android.network.auth.AuthStyle
import retrofit2.http.Body
import retrofit2.http.POST

@Auth(AuthStyle.BASIC)
interface BindEmailService {
    @POST("v2/auth/bind/send")
    suspend fun verifyEmail(
        @Body body: Map<String, String>
    ): BaseResponse<BindAccountResponse>

    @POST("v2/auth/bind/verification")
    suspend fun verifyEmailCode(
        @Body body: Map<String, String>
    ): BaseResponse<Any>

    @POST("v2/auth/bind/sms/send")
    suspend fun verifyPhone(
        @Body body: Map<String, String>
    ): BaseResponse<BindAccountResponse>

    @POST("v2/auth/bind/sms/verification")
    suspend fun verifyPhoneCode(
        @Body body: Map<String, String>
    ): BaseResponse<Any>
}
