package com.difft.android.login.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.AuthCredentials
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.login.PowUtil
import com.difft.android.login.R
import com.difft.android.login.data.AccountData
import com.difft.android.login.data.EmailVerifyData
import com.difft.android.login.data.NonceInfoRequestBody
import com.difft.android.login.repo.LoginRepo
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.viewmodel.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import org.signal.libsignal.protocol.util.KeyHelper
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.util.Util
import javax.inject.Inject
import org.thoughtcrime.securesms.cryptonew.EncryptionDataManager
import com.difft.android.websocket.internal.push.PreKeyState
import com.difft.android.base.utils.Base64


@HiltViewModel
class LoginViewModel @Inject constructor() : ViewModel() {

    @Inject
    lateinit var loginRepo: LoginRepo

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var encryptionDataManager: EncryptionDataManager

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    private val mInviteCodeLiveData = MutableLiveData<Resource<Any>>()
    internal val inviteCodeLiveData: LiveData<Resource<Any>> = mInviteCodeLiveData

    private val mSignInLiveData = MutableLiveData<Resource<AccountData>>()
    internal val signInLiveData: LiveData<Resource<AccountData>> = mSignInLiveData

    private val mVerifyEmailLiveData = MutableLiveData<Resource<String>>()
    internal val verifyEmailLiveData: LiveData<Resource<String>> = mVerifyEmailLiveData

    private val mLoginEmailCodeLiveData = MutableLiveData<Resource<EmailVerifyData>>()
    internal val loginEmailCodeLiveData: LiveData<Resource<EmailVerifyData>> = mLoginEmailCodeLiveData

    private val mVerifyPhoneLiveData = MutableLiveData<Resource<String>>()
    internal val verifyPhoneLiveData: LiveData<Resource<String>> = mVerifyPhoneLiveData

    private val mLoginPhoneCodeLiveData = MutableLiveData<Resource<EmailVerifyData>>()
    internal val loginPhoneCodeLiveData: LiveData<Resource<EmailVerifyData>> = mLoginPhoneCodeLiveData

    /**
     * 一键注册
     */
    fun signUpByNonceCode() {
        mSignInLiveData.value = Resource.loading()
        viewModelScope.launch {
            try {
                // Step 1: 获取 nonce info
                val nonceInfoResult = loginRepo.getNonceInfo().await()
                if (nonceInfoResult.status != 0) {
                    L.e { "[login] signUpByNonceCode -> getNonceInfo failed, status=${nonceInfoResult.status}, reason=${nonceInfoResult.reason}" }
                    mSignInLiveData.value = Resource.error(NetworkException(nonceInfoResult.status, nonceInfoResult.reason ?: ""))
                    return@launch
                }
                val nonceData = nonceInfoResult.data
                if (nonceData == null) {
                    L.e { "[login] signUpByNonceCode -> getNonceInfo returned null data" }
                    mSignInLiveData.value = Resource.error(NetworkException(message = ResUtils.getString(com.difft.android.network.R.string.chat_net_error)))
                    return@launch
                }

                // Step 2: 生成 nonce code
                val uuid = nonceData.uuid
                val solution = PowUtil.generateSolution(uuid ?: "", nonceData.timestamp, nonceData.version, nonceData.difficulty)
                val nonceCodeResult = loginRepo.generateNonceCode(NonceInfoRequestBody(uuid, solution)).await()
                if (nonceCodeResult.status != 0) {
                    L.e { "[login] signUpByNonceCode -> generateNonceCode failed, status=${nonceCodeResult.status}, reason=${nonceCodeResult.reason}" }
                    mSignInLiveData.value = Resource.error(NetworkException(nonceCodeResult.status, nonceCodeResult.reason ?: ""))
                    return@launch
                }
                val code = nonceCodeResult.data?.code
                if (code.isNullOrBlank()) {
                    L.e { "[login] signUpByNonceCode -> generateNonceCode returned null code" }
                    mSignInLiveData.value = Resource.error(NetworkException(message = ResUtils.getString(com.difft.android.network.R.string.chat_net_error)))
                    return@launch
                }

                // Step 3: 验证邀请码
                verifyInvitationCode(code)
            } catch (e: Exception) {
                L.e { "[login] signUpByNonceCode -> error: ${e.message}" }
                mSignInLiveData.value = Resource.error(NetworkException(message = e.message ?: ""))
            }
        }
    }

    /**
     * 验证邀请码是否有效
     * @param invitationCode 邀请码
     */
    private suspend fun verifyInvitationCode(invitationCode: String) {
        mInviteCodeLiveData.value = Resource.loading()
        try {
            val result = loginRepo.verifyInvitationCode(invitationCode).await()
            if (result.status == 0) {
                mInviteCodeLiveData.value = Resource.success()
                val vcode = result.data?.vcode
                val account = result.data?.account
                if (!vcode.isNullOrBlank() && !account.isNullOrBlank()) {
                    signIn(vcode, account, Util.getSecret(16), true, null, null)
                } else {
                    L.e { "[login] verifyInvitationCode -> returned null vcode or account, vcodeIsEmpty=${vcode.isNullOrBlank()}, accountIsEmpty=${account.isNullOrBlank()}" }
                    mSignInLiveData.value = Resource.error(NetworkException(message = ResUtils.getString(com.difft.android.network.R.string.chat_net_error)))
                }
            } else {
                L.e { "[login] verifyInvitationCode -> failed with status=${result.status}, reason=${result.reason}" }
                val error = NetworkException(result.status, result.reason ?: "")
                mInviteCodeLiveData.value = Resource.error(error)
                mSignInLiveData.value = Resource.error(error)
            }
        } catch (e: Exception) {
            L.e { "[login] verifyInvitationCode -> error: ${e.message}" }
            val error = NetworkException(message = e.message ?: "")
            mInviteCodeLiveData.value = Resource.error(error)
            mSignInLiveData.value = Resource.error(error)
        }
    }

    companion object {
        const val DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE = -10010
    }

    /**
     * 检查是否是不同账号登录
     */
    private fun checkIsDifferentAccountLogin(newAccount: String?): Boolean {
        val oldAccount = userManager.getUserData()?.account
        return !oldAccount.isNullOrEmpty() && !newAccount.isNullOrEmpty() && oldAccount != newAccount
    }

    /**
     * 使用账号登录
     */
    private fun signIn(
        verificationCode: String,
        account: String,
        password: String,
        newUser: Boolean,
        email: String?,
        phone: String?
    ) {
        if (checkIsDifferentAccountLogin(account)) {
            L.w { "[login] signIn -> different account detected" }
            mSignInLiveData.value = Resource.error(NetworkException(errorCode = DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE))
            return
        }
        mSignInLiveData.value = Resource.loading()

        viewModelScope.launch {
            try {
                // 准备登录参数
                val basicAuth = AuthCredentials(account, password).asBasic()
                val name = ""
                val signalingKey = Util.getSecret(52)
                val registrationId = getNewRegistrationId()

                // Step 1: Sign in
                val signInResult = loginRepo.signIn(verificationCode, basicAuth, signalingKey, name, registrationId).await()
                if (signInResult.status != 0) {
                    L.e { "[login] signIn -> signIn API failed, status=${signInResult.status}, reason=${signInResult.reason}" }
                    throw NetworkException(signInResult.status, signInResult.reason ?: "")
                }

                // Step 2: Register pre keys
                val preKeyResult = registerPreKeys(basicAuth).await()
                if (preKeyResult.status != 0) {
                    L.e { "[login] signIn -> registerPreKeys failed, status=${preKeyResult.status}, reason=${preKeyResult.reason}" }
                    throw NetworkException(preKeyResult.status, preKeyResult.reason ?: "")
                }

                // Step 3: Fetch auth token
                val tokenResult = httpClient.httpService.fetchAuthToken(basicAuth)
                if (tokenResult.status == 0) {
                    userManager.update {
                        this.baseAuth = basicAuth
                        this.account = account
                        this.signalingKey = signalingKey
                        this.microToken = tokenResult.data?.token
                        this.email = email
                        this.phoneNumber = phone
                    }
                    mSignInLiveData.postValue(Resource.success(AccountData(newUser)))
                } else {
                    L.e { "[login] signIn -> fetchAuthToken failed, status=${tokenResult.status}, reason=${tokenResult.reason}" }
                    mSignInLiveData.postValue(Resource.error(NetworkException(message = tokenResult.reason ?: "")))
                }
            } catch (e: Exception) {
                L.e { "[login] signIn -> error: ${e.message}" }
                val code = (e as? NetworkException)?.errorCode
                val message = when (code) {
                    403 -> ResUtils.getString(R.string.login_error_not_exist)
                    400 -> ResUtils.getString(R.string.login_error_verification_failed)
                    413 -> ResUtils.getString(R.string.login_error_too_many_attempts)
                    else -> ResUtils.getString(com.difft.android.network.R.string.chat_net_error)
                }
                mSignInLiveData.postValue(Resource.error(NetworkException(message = message)))
            }
        }
    }

    private fun getNewRegistrationId(): Int {
        val registrationId = KeyHelper.generateRegistrationId(false)
        return registrationId
    }

    private fun registerPreKeys(basicAuth: String): Single<BaseResponse<Any>> {
        val identityKeyPair = IdentityKeyUtil.generateIdentityKeyPair()
        encryptionDataManager.updateAciIdentityKey(identityKeyPair)

        // 生成PreKeyState
        val identityKey = identityKeyPair.publicKey
        val identityKeyString = Base64.encodeBytesWithoutPadding(identityKey.serialize())
        val signByteArray = identityKeyPair.privateKey.calculateSignature(identityKeyString.toByteArray())
        val identityKeySign = Base64.encodeBytesWithoutPadding(signByteArray)

        val prekeyState = PreKeyState(identityKey, identityKeySign)

        return loginRepo.registerPreKeys(basicAuth, prekeyState)
    }

    /**
     * 使用手机号登录时，发送验证码
     */
    fun verifyPhone(phone: String) {
        mVerifyPhoneLiveData.value = Resource.loading()
        viewModelScope.launch {
            try {
                val result = loginRepo.verifyPhone(phone).await()
                if (result.status == 0) {
                    mVerifyPhoneLiveData.value = Resource.success(phone)
                } else {
                    mVerifyPhoneLiveData.value = Resource.error(NetworkException(message = result.reason ?: ""))
                }
            } catch (e: Exception) {
                mVerifyPhoneLiveData.value = Resource.error(NetworkException(message = e.message ?: ""))
            }
        }
    }

    /**
     * 登录时验证手机号验证码
     */
    fun verifyPhoneCodeWithLogin(phone: String, code: String) {
        mLoginPhoneCodeLiveData.value = Resource.loading()
        viewModelScope.launch {
            try {
                val result = loginRepo.verifyPhoneCode(phone, code).await()
                if (result.status == 0) {
                    mLoginPhoneCodeLiveData.value = Resource.success(result.data)
                    if (result.data?.nextStep == 0) {
                        verifyInvitationCode(result.data?.invitationCode ?: "")
                    } else {
                        signIn(
                            result.data?.verificationCode ?: "",
                            result.data?.account ?: "",
                            Util.getSecret(16),
                            false,
                            null,
                            phone
                        )
                    }
                } else {
                    mLoginPhoneCodeLiveData.value = Resource.error(NetworkException(message = result.reason ?: ""))
                }
            } catch (e: Exception) {
                mLoginPhoneCodeLiveData.value = Resource.error(NetworkException(message = e.message ?: ""))
            }
        }
    }

    /**
     * 使用邮箱登录时，发送验证码
     */
    fun verifyEmail(email: String) {
        mVerifyEmailLiveData.value = Resource.loading()
        viewModelScope.launch {
            try {
                val result = loginRepo.verifyEmail(email).await()
                if (result.status == 0) {
                    mVerifyEmailLiveData.value = Resource.success(email)
                } else {
                    mVerifyEmailLiveData.value = Resource.error(NetworkException(message = result.reason ?: ""))
                }
            } catch (e: Exception) {
                mVerifyEmailLiveData.value = Resource.error(NetworkException(message = e.message ?: ""))
            }
        }
    }

    /**
     * 登录时验证邮箱验证码
     */
    fun verifyEmailCodeWithLogin(email: String, emailCode: String) {
        mLoginEmailCodeLiveData.value = Resource.loading()
        viewModelScope.launch {
            try {
                val result = loginRepo.verifyEmailCode(email, emailCode).await()
                if (result.status == 0) {
                    mLoginEmailCodeLiveData.value = Resource.success(result.data)
                    if (result.data?.nextStep == 0) {
                        verifyInvitationCode(result.data?.invitationCode ?: "")
                    } else {
                        signIn(
                            result.data?.verificationCode ?: "",
                            result.data?.account ?: "",
                            Util.getSecret(16),
                            false,
                            email,
                            null
                        )
                    }
                } else {
                    mLoginEmailCodeLiveData.value = Resource.error(NetworkException(message = result.reason ?: ""))
                }
            } catch (e: Exception) {
                mLoginEmailCodeLiveData.value = Resource.error(NetworkException(message = e.message ?: ""))
            }
        }
    }

    fun verifyAccount(account: String, countryCode: String) {
        if (ValidatorUtil.isPhone(account)) {
            verifyPhone(countryCode + account)
        } else if (ValidatorUtil.isEmail(account)) {
            verifyEmail(account)
        } else {
            viewModelScope.launch {
                verifyInvitationCode(account)
            }
        }
    }
}