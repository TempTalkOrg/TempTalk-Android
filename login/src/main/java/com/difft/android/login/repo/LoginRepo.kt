package com.difft.android.login.repo

import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
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
import com.difft.android.network.requests.TransferProof
import com.difft.android.websocket.internal.push.PreKeyState
import com.difft.android.websocket.internal.util.JsonUtil
import javax.inject.Inject

class LoginRepo @Inject constructor() {

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    private val loginService by lazy {
        httpClient.getService(LoginService::class.java)
    }

    suspend fun getNonceInfo(): BaseResponse<NonceInfo> =
        loginService.getNonceInfo()

    suspend fun generateNonceCode(nonceInfoRequest: NonceInfoRequestBody): BaseResponse<GenerateNonceCodeResponse> =
        loginService.generateNonceCode(nonceInfoRequest)

    suspend fun verifyInvitationCode(code: String): BaseResponse<CodeResponse> =
        loginService.verifyInvitationCode(code)

    /**
     * `PUT /v1/accounts/code/{vcode}`. With a [transferProof] the server keeps the account's
     * secondary devices signed in when the proof verifies; its verdict rides in a header and is only
     * logged, since it changes nothing here. Non-2xx throws; a bodiless success is still a success.
     */
    suspend fun signIn(
        vcode: String,
        basicAuth: String,
        signalingKey: String,
        name: String,
        registrationId: Int,
        transferProof: TransferProof? = null,
    ): BaseResponse<Any> {
        val response = loginService.signin(vcode, basicAuth, SignInRequestBody(signalingKey, name, true, registrationId, transferProof))
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        if (transferProof != null) {
            L.i { "[login] signIn with transferProof → $CONTINUATION_APPLIED_HEADER=${response.headers()[CONTINUATION_APPLIED_HEADER]}" }
        }
        return response.body() ?: BaseResponse(ver = 0, status = 0, reason = null, data = null)
    }

    suspend fun verifyEmail(email: String): BaseResponse<Any> =
        loginService.verifyEmail(mapOf("email" to email))

    /**
     * Every verification asks for a transfer token instead of a one-time code (`supportTransfer = 1`):
     * while the token is unredeemed the account's other device keeps its session, and only
     * [renewAccount] revokes it. A server with no other device answers with the plain code instead.
     */
    suspend fun verifyEmailCode(email: String, emailCode: String): BaseResponse<EmailVerifyData> =
        loginService.verifyEmailCode(verificationBody("email" to email, code = emailCode))

    suspend fun verifyPhone(phone: String): BaseResponse<Any> =
        loginService.verifyPhone(mapOf("phone" to phone))

    suspend fun verifyPhoneCode(phone: String, phoneCode: String): BaseResponse<EmailVerifyData> =
        loginService.verifyPhoneCode(verificationBody("phone" to phone, code = phoneCode))

    /** Turns a transfer token into the account + one-time code; the server may revoke other devices here. */
    suspend fun renewAccount(token: String): BaseResponse<EmailVerifyData> =
        loginService.renewAccount(mapOf("token" to token))

    private fun verificationBody(target: Pair<String, String>, code: String): Map<String, Any> =
        mapOf(target.first to target.second, "verificationCode" to code, "supportTransfer" to 1)

    suspend fun registerPreKeys(basicAuth: String, preKeyState: PreKeyState): BaseResponse<Any> =
        loginService.registerPreKeys(basicAuth, JsonUtil.toJson(preKeyState))

    suspend fun renewIdentityKey(body: String): BaseResponse<Any> = loginService.resetIdentity(body)

    companion object {
        /** `true` when the server kept the account's secondary devices on this sign-in; `false` for any other outcome. */
        const val CONTINUATION_APPLIED_HEADER = "X-Continuation-Applied"
    }

    private val httpService by lazy {
        httpClient.getService(HttpService::class.java)
    }

    suspend fun setProfile(): BaseResponse<Any> {
        return httpService.fetchSetProfile(
            ProfileRequestBody(
                meetingVersion = LCallConstants.CALL_VERSION,
                msgEncVersion = 2, // 1: Encryption Disabled, 2: Encryption Enabled
            )
        )
    }

}
