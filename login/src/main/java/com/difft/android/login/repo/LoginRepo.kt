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
import io.reactivex.rxjava3.core.Single
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

    fun getNonceInfo(): Single<BaseResponse<NonceInfo>> =
        loginService.getNonceInfo()

    fun generateNonceCode(nonceInfoRequest: NonceInfoRequestBody): Single<BaseResponse<GenerateNonceCodeResponse>> =
        loginService.generateNonceCode(nonceInfoRequest)

    fun verifyInvitationCode(code: String): Single<BaseResponse<CodeResponse>> =
        loginService.verifyInvitationCode(code)

    fun signIn(vcode: String, basicAuth: String, signalingKey: String, name: String, registrationId: Int): Single<BaseResponse<Any>> =
        loginService.signin(vcode, basicAuth, SignInRequestBody(signalingKey, name, true, registrationId))

    fun verifyEmail(email: String): Single<BaseResponse<Any>> =
        loginService.verifyEmail(mapOf("email" to email))

    fun verifyEmailCode(email: String, emailCode: String): Single<BaseResponse<EmailVerifyData>> =
        loginService.verifyEmailCode(mapOf("email" to email, "verificationCode" to emailCode))

    fun verifyPhone(phone: String): Single<BaseResponse<Any>> =
        loginService.verifyPhone(mapOf("phone" to phone))

    fun verifyPhoneCode(phone: String, phoneCode: String): Single<BaseResponse<EmailVerifyData>> =
        loginService.verifyPhoneCode(mapOf("phone" to phone, "verificationCode" to phoneCode))

    fun registerPreKeys(basicAuth: String, preKeyState: PreKeyState): Single<BaseResponse<Any>> =
        loginService.registerPreKeys(basicAuth, JsonUtil.toJson(preKeyState))

    fun renewIdentityKey(token: String, body: String): Single<BaseResponse<Any>> = loginService.resetIdentity(token, body)

    private val httpService by lazy {
        httpClient.getService(HttpService::class.java)
    }

    fun setProfile(): Single<BaseResponse<Any>> {
        return httpService.fetchSetProfile(
            SecureSharedPrefsUtil.getBasicAuth(),
            ProfileRequestBody(
                meetingVersion = LCallConstants.CALL_VERSION,
                msgEncVersion = 2, // 1: Encryption Disabled, 2: Encryption Enabled
            )
        )
    }

}