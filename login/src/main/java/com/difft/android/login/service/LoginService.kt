package com.difft.android.login.service

import com.difft.android.login.data.CodeResponse
import com.difft.android.login.data.EmailVerifyData
import com.difft.android.login.data.GenerateNonceCodeResponse
import com.difft.android.login.data.NonceInfo
import com.difft.android.login.data.NonceInfoRequestBody
import com.difft.android.network.BaseResponse
import com.difft.android.network.requests.SignInRequestBody
import io.reactivex.rxjava3.core.Single
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface LoginService {
    @GET("v1/accounts/invitation/{invitationCode}")
    fun verifyInvitationCode(@Path("invitationCode") invitationCode: String): Single<BaseResponse<CodeResponse>>

    @PUT("v1/accounts/code/{vcode}")
    fun signin(
        @Path("vcode") vcode: String,
        @Header("Authorization") baseAuth: String,
        @Body body: SignInRequestBody
    ): Single<BaseResponse<Any>>

    @POST("v2/auth/login/send")
    fun verifyEmail(@Body body: Map<String, String>): Single<BaseResponse<Any>>

    @POST("v2/auth/login/verification")
    fun verifyEmailCode(@Body body: Map<String, String>): Single<BaseResponse<EmailVerifyData>>

    @POST("v2/auth/login/sms/send")
    fun verifyPhone(@Body body: Map<String, String>): Single<BaseResponse<Any>>

    @POST("v2/auth/login/sms/verification")
    fun verifyPhoneCode(@Body body: Map<String, String>): Single<BaseResponse<EmailVerifyData>>

    @Headers("Content-Type:application/json")
    @PUT("v2/keys")
    fun registerPreKeys(
        @Header("Authorization") baseAuth: String,
        @Body body: String
    ): Single<BaseResponse<Any>>

    @Headers("Content-Type:application/json")
    @POST("v2/keys/resetIdentity")
    fun resetIdentity(
        @Header("Authorization") baseAuth: String,
        @Body body: String
    ): Single<BaseResponse<Any>>

    @POST("v1/accounts/generateNonceCode")
    fun generateNonceCode(
        @Body nonceInfoRequest: NonceInfoRequestBody
    ): Single<BaseResponse<GenerateNonceCodeResponse>>

    @POST("v1/accounts/getNonceInfo")
    fun getNonceInfo(): Single<BaseResponse<NonceInfo>>
}