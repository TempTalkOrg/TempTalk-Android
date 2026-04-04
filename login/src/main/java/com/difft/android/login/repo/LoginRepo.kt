package com.difft.android.login.repo

import com.difft.android.base.call.LCallConstants
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.login.data.CodeResponse
import com.difft.android.login.data.EmailVerifyData
import com.difft.android.login.data.GenerateNonceCodeResponse
import com.difft.android.login.data.NonceInfo
import com.difft.android.login.data.NonceInfoRequestBody
import com.difft.android.login.service.LoginService
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.ProfileRequestBody
import com.difft.android.network.requests.SignInRequestBody
import com.difft.android.websocket.internal.push.PreKeyState
import com.difft.android.websocket.internal.util.JsonUtil
import javax.inject.Inject

class LoginRepo @Inject constructor() {

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    @Inject
    lateinit var userManager: UserManager

    private val loginService by lazy {
        httpClient.getService(LoginService::class.java)
    }

    suspend fun getNonceInfo(): BaseResponse<NonceInfo> =
        loginService.getNonceInfo()

    suspend fun generateNonceCode(nonceInfoRequest: NonceInfoRequestBody): BaseResponse<GenerateNonceCodeResponse> =
        loginService.generateNonceCode(nonceInfoRequest)

    suspend fun verifyInvitationCode(code: String): BaseResponse<CodeResponse> =
        loginService.verifyInvitationCode(code)

    suspend fun signIn(vcode: String, basicAuth: String, signalingKey: String, name: String, registrationId: Int): BaseResponse<Any> =
        loginService.signin(vcode, basicAuth, SignInRequestBody(signalingKey, name, true, registrationId))

    suspend fun verifyEmail(email: String): BaseResponse<Any> =
        loginService.verifyEmail(mapOf("email" to email))

    suspend fun verifyEmailCode(email: String, emailCode: String): BaseResponse<EmailVerifyData> =
        loginService.verifyEmailCode(mapOf("email" to email, "verificationCode" to emailCode))

    suspend fun verifyPhone(phone: String): BaseResponse<Any> =
        loginService.verifyPhone(mapOf("phone" to phone))

    suspend fun verifyPhoneCode(phone: String, phoneCode: String): BaseResponse<EmailVerifyData> =
        loginService.verifyPhoneCode(mapOf("phone" to phone, "verificationCode" to phoneCode))

    suspend fun registerPreKeys(basicAuth: String, preKeyState: PreKeyState): BaseResponse<Any> =
        loginService.registerPreKeys(basicAuth, JsonUtil.toJson(preKeyState))

    suspend fun renewIdentityKey(token: String, body: String): BaseResponse<Any> = loginService.resetIdentity(token, body)

    private val httpService by lazy {
        httpClient.getService(HttpService::class.java)
    }

    suspend fun setProfile(): BaseResponse<Any> {
        return httpService.fetchSetProfile(
            SecureSharedPrefsUtil.getBasicAuth(),
            ProfileRequestBody(
                meetingVersion = LCallConstants.CALL_VERSION,
                msgEncVersion = 2, // 1: Encryption Disabled, 2: Encryption Enabled
            )
        )
    }

}
