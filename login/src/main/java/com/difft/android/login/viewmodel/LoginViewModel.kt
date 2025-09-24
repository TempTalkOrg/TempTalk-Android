package com.difft.android.login.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.AuthCredentials
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RxUtil
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
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.signal.libsignal.protocol.util.KeyHelper
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.util.Util
import javax.inject.Inject
import org.thoughtcrime.securesms.cryptonew.EncryptionDataManager
import com.difft.android.websocket.internal.push.PreKeyState
import com.difft.android.websocket.util.Base64


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
        loginRepo.getNonceInfo()
            .concatMap { info ->
                if (info.status == 0) {
                    var uuid: String? = null
                    var solution: String? = null
                    info.data?.let {
                        uuid = it.uuid
                        solution = PowUtil.generateSolution(uuid ?: "", it.timestamp, it.version, it.difficulty)
                    }
                    loginRepo.generateNonceCode(NonceInfoRequestBody(uuid, solution))
                } else {
                    Single.error(NetworkException(info.status, info.reason ?: ""))
                }
            }
            .compose(RxUtil.getSingleSchedulerComposer())
            .subscribe({
                if (it.status == 0) {
                    it.data?.code?.let { code ->
                        verifyInvitationCode(code)
                    }
                } else {
                    mSignInLiveData.value = Resource.error(NetworkException(it.status, it.reason ?: ""))
                }
            }, {
                it.printStackTrace()
                mSignInLiveData.value = Resource.error(NetworkException(message = it.message ?: ""))
            }).also {
                compositeDisposable.add(it)
            }
    }

    /**
     * 验证邀请码是否有效
     * @param invitationCode 邀请码
     */
    private fun verifyInvitationCode(invitationCode: String) {
        mInviteCodeLiveData.value = Resource.loading()
        loginRepo.verifyInvitationCode(invitationCode)
            .observeOn(AndroidSchedulers.mainThread()).subscribe({
                if (it.status == 0) {
                    mInviteCodeLiveData.value = Resource.success()
                    signIn(
                        it.data?.vcode ?: "",
                        it.data?.account ?: "",
                        Util.getSecret(16),
                        true,
                        null,
                        null
                    )
                } else {
                    mInviteCodeLiveData.value = Resource.error(
                        NetworkException(
                            it.status, it.reason ?: ""
                        )
                    )
                }
            }, {
                mInviteCodeLiveData.value =
                    Resource.error(NetworkException(message = it.message ?: ""))
            }).also {
                compositeDisposable.add(it)
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
            mSignInLiveData.value = Resource.error(NetworkException(errorCode = DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE))
            return
        }
        mSignInLiveData.value = Resource.loading()
        val basicAuth = AuthCredentials(account, password).asBasic()
        val name = ""
        val signalingKey = Util.getSecret(52)
        val registrationId = getNewRegistrationId()
        loginRepo.signIn(verificationCode, basicAuth, signalingKey, name, registrationId)
            .concatMap {
                if (it.status == 0) {
                    registerPreKeys(basicAuth)
                } else {
                    Single.error(NetworkException(it.status, it.reason ?: ""))
                }
            }
            .concatMap {
                if (it.status == 0) {
                    httpClient.httpService.fetchAuthToken(basicAuth)
                } else {
                    Single.error(NetworkException(it.status, it.reason ?: ""))
                }
            }
            .compose(RxUtil.getSingleSchedulerComposer())
            .subscribe({
                if (it.status == 0) {
                    userManager.update {
                        this.baseAuth = basicAuth
                        this.account = account
                        this.signalingKey = signalingKey
                        this.microToken = it.data?.token
                        this.email = email
                        this.phoneNumber = phone
                    }
                    mSignInLiveData.value = Resource.success(AccountData(newUser))
                } else {
                    mSignInLiveData.value = Resource.error(NetworkException(message = it.reason ?: ""))
                }
            }, {
                it.printStackTrace()
                L.i { "[login] signIn -> error: ${it.stackTraceToString()}" }
                val code = (it as? NetworkException)?.errorCode
                val message = when (code) {
                    403 -> {
                        ResUtils.getString(R.string.login_error_not_exist)
                    }

                    400 -> {
                        ResUtils.getString(R.string.login_error_verification_failed)
                    }

                    413 -> {
                        ResUtils.getString(R.string.login_error_too_many_attempts)
                    }

                    else -> {
                        ResUtils.getString(com.difft.android.network.R.string.chat_net_error)
                    }
                }
                mSignInLiveData.value = Resource.error(NetworkException(message = message))
            }).also {
                compositeDisposable.add(it)
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
        loginRepo.verifyPhone(phone)
            .compose(RxUtil.getSingleSchedulerComposer())
            .subscribe({
                if (it.status == 0) {
                    mVerifyPhoneLiveData.value = Resource.success(phone)
                } else {
                    mVerifyPhoneLiveData.value = Resource.error(NetworkException(message = it.reason ?: ""))
                }
            }, {
                mVerifyPhoneLiveData.value = Resource.error(NetworkException(message = it.message ?: ""))
            }).also {
                compositeDisposable.add(it)
            }
    }

    /**
     * 登录时验证手机号验证码
     */
    fun verifyPhoneCodeWithLogin(phone: String, code: String) {
        mLoginPhoneCodeLiveData.value = Resource.loading()
        loginRepo.verifyPhoneCode(phone, code)
            .compose(RxUtil.getSingleSchedulerComposer())
            .subscribe({
                if (it.status == 0) {
                    mLoginPhoneCodeLiveData.value = Resource.success(it.data)
                    if (it.data?.nextStep == 0) {
                        verifyInvitationCode(it.data?.invitationCode ?: "")
                    } else {
                        signIn(
                            it.data?.verificationCode ?: "",
                            it.data?.account ?: "",
                            Util.getSecret(16),
                            false,
                            null,
                            phone
                        )
                    }
                } else {
                    mLoginPhoneCodeLiveData.value = Resource.error(NetworkException(message = it.reason ?: ""))
                }
            }, {
                mLoginPhoneCodeLiveData.value = Resource.error(NetworkException(message = it.message ?: ""))
            }).also {
                compositeDisposable.add(it)
            }
    }

    /**
     * 使用邮箱登录时，发送验证码
     */
    fun verifyEmail(email: String) {
        mVerifyEmailLiveData.value = Resource.loading()
        loginRepo.verifyEmail(email)
            .observeOn(AndroidSchedulers.mainThread()).subscribe({
                if (it.status == 0) {
                    mVerifyEmailLiveData.value = Resource.success(email)
                } else {
                    mVerifyEmailLiveData.value = Resource.error(NetworkException(message = it.reason ?: ""))
                }
            }, {
                mVerifyEmailLiveData.value =
                    Resource.error(NetworkException(message = it.message ?: ""))
            }).also {
                compositeDisposable.add(it)
            }
    }

    /**
     * 登录时验证邮箱验证码
     */
    fun verifyEmailCodeWithLogin(email: String, emailCode: String) {
        mLoginEmailCodeLiveData.value = Resource.loading()
        loginRepo.verifyEmailCode(email, emailCode)
            .observeOn(AndroidSchedulers.mainThread()).subscribe({
                if (it.status == 0) {
                    mLoginEmailCodeLiveData.value = Resource.success(it.data)
                    if (it.data?.nextStep == 0) {
                        verifyInvitationCode(it.data?.invitationCode ?: "")
                    } else {
                        signIn(
                            it.data?.verificationCode ?: "",
                            it.data?.account ?: "",
                            Util.getSecret(16),
                            false,
                            email,
                            null
                        )
                    }
                } else {
                    mLoginEmailCodeLiveData.value = Resource.error(NetworkException(message = it.reason ?: ""))
                }
            }, {
                mLoginEmailCodeLiveData.value = Resource.error(NetworkException(message = it.message ?: ""))
            }).also {
                compositeDisposable.add(it)
            }
    }

    fun verifyAccount(account: String, countryCode: String) {
        if (ValidatorUtil.isPhone(account)) {
            verifyPhone(countryCode + account)
        } else if (ValidatorUtil.isEmail(account)) {
            verifyEmail(account)
        } else {
            verifyInvitationCode(account)
        }
    }

    private val compositeDisposable: CompositeDisposable by lazy { CompositeDisposable() }
    override fun onCleared() {
        super.onCleared()
        compositeDisposable.dispose()
    }
}