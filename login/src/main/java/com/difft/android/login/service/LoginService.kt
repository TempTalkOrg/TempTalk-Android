package com.difft.android.login.service

import com.difft.android.login.data.CodeResponse
import com.difft.android.login.data.EmailVerifyData
import com.difft.android.login.data.GenerateNonceCodeResponse
import com.difft.android.login.data.NonceInfo
import com.difft.android.login.data.NonceInfoRequestBody
import com.difft.android.network.BaseResponse
import com.difft.android.network.auth.Auth
import com.difft.android.network.auth.AuthStyle
import com.difft.android.network.requests.SignInRequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path






@Auth(AuthStyle.NONE)
interface LoginService {
    @GET("v1/accounts/invitation/{invitationCode}")
    suspend fun verifyInvitationCode(@Path("invitationCode") invitationCode: String): BaseResponse<CodeResponse>

    




    @Auth(AuthStyle.EXPLICIT)
    @PUT("v1/accounts/code/{vcode}")
    suspend fun signin(
        @Path("vcode") vcode: String,
        @Header("Authorization") baseAuth: String,
        @Body body: SignInRequestBody
    ): Response<BaseResponse<Any>>

    @POST("v2/auth/login/send")
    suspend fun verifyEmail(@Body body: Map<String, String>): BaseResponse<Any>

    @POST("v2/auth/login/verification")
    suspend fun verifyEmailCode(@Body body: Map<String, @JvmSuppressWildcards Any>): BaseResponse<EmailVerifyData>

    
    @PUT("v1/accounts/renew/")
    suspend fun renewAccount(@Body body: Map<String, String>): BaseResponse<EmailVerifyData>

    @POST("v2/auth/login/sms/send")
    suspend fun verifyPhone(@Body body: Map<String, String>): BaseResponse<Any>

    @POST("v2/auth/login/sms/verification")
    suspend fun verifyPhoneCode(@Body body: Map<String, @JvmSuppressWildcards Any>): BaseResponse<EmailVerifyData>

    
    @Auth(AuthStyle.EXPLICIT)
    @Headers("Content-Type:application/json")
    @PUT("v2/keys")
    suspend fun registerPreKeys(
        @Header("Authorization") baseAuth: String,
        @Body body: String
    ): BaseResponse<Any>

    @Auth(AuthStyle.BASIC)
    @Headers("Content-Type:application/json")
    @POST("v2/keys/resetIdentity")
    suspend fun resetIdentity(
        @Body body: String
    ): BaseResponse<Any>

    @POST("v1/accounts/generateNonceCode")
    suspend fun generateNonceCode(
        @Body nonceInfoRequest: NonceInfoRequestBody
    ): BaseResponse<GenerateNonceCodeResponse>

    @POST("v1/accounts/getNonceInfo")
    suspend fun getNonceInfo(): BaseResponse<NonceInfo>
}
