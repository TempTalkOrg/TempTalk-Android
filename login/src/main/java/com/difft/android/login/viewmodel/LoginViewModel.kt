package com.difft.android.login.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.MigrationImportLauncher
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.AuthCredentials
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.login.PowUtil
import com.difft.android.login.R
import com.difft.android.login.data.AccountData
import com.difft.android.login.data.EmailVerifyData
import com.difft.android.login.data.NonceInfoRequestBody
import com.difft.android.login.entry.AccountSwitchPolicy
import com.difft.android.login.entry.LoginEntryPolicy
import com.difft.android.login.entry.PendingLoginStore
import com.difft.android.login.repo.LoginRepo
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.viewmodel.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.signal.libsignal.protocol.util.KeyHelper
import com.difft.android.chat.crypto.IdentityKeyUtil
import com.difft.android.chat.util.Util
import javax.inject.Inject
import com.difft.android.chat.cryptonew.EncryptionDataManager
import com.difft.android.chat.gif.favorite.FavoriteWriteRepository
import com.difft.android.websocket.internal.push.PreKeyState
import com.difft.android.base.utils.Base64
import com.difft.android.login.transfer.TransferProofSigner
import com.difft.android.network.requests.TransferProof


@HiltViewModel
class LoginViewModel @Inject constructor() : ViewModel() {

    @Inject
    lateinit var loginRepo: LoginRepo

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var encryptionDataManager: dagger.Lazy<EncryptionDataManager>

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: dagger.Lazy<ChativeHttpClient>

    @Inject
    lateinit var migrationImportLauncher: MigrationImportLauncher

    
    @Inject
    lateinit var pendingLoginStore: PendingLoginStore

    




    private val mMigrationPromptLiveData = MutableLiveData<PendingSignIn>()
    internal val migrationPromptLiveData: LiveData<PendingSignIn> = mMigrationPromptLiveData

    private var migrationPromptConsumed = false

    



    internal fun consumeMigrationPrompt(): Boolean {
        if (migrationPromptConsumed) return false
        migrationPromptConsumed = true
        return true
    }

    




    @Volatile
    var signInIsRestorePath: Boolean = false
        private set

    




    @Volatile
    var restoreUseInstalledIdentity: Boolean = true
        private set

    @Inject
    lateinit var favoriteWriteRepo: dagger.Lazy<FavoriteWriteRepository>

    private val mAccountInvalidLiveData = MutableLiveData<Resource<Any>>()
    internal val accountInvalidLiveData: LiveData<Resource<Any>> = mAccountInvalidLiveData

    private val mSignInLiveData = MutableLiveData<Resource<AccountData>>()
    val signInLiveData: LiveData<Resource<AccountData>> = mSignInLiveData

    private val mVerifyEmailLiveData = MutableLiveData<Resource<String>>()
    internal val verifyEmailLiveData: LiveData<Resource<String>> = mVerifyEmailLiveData

    private val mLoginEmailCodeLiveData = MutableLiveData<Resource<EmailVerifyData>>()
    internal val loginEmailCodeLiveData: LiveData<Resource<EmailVerifyData>> = mLoginEmailCodeLiveData

    private val mVerifyPhoneLiveData = MutableLiveData<Resource<String>>()
    internal val verifyPhoneLiveData: LiveData<Resource<String>> = mVerifyPhoneLiveData

    private val mLoginPhoneCodeLiveData = MutableLiveData<Resource<EmailVerifyData>>()
    internal val loginPhoneCodeLiveData: LiveData<Resource<EmailVerifyData>> = mLoginPhoneCodeLiveData

    







    private fun openSignInChain() {
        mSignInLiveData.value = Resource.loading()
    }

    fun signUpByNonceCode() {
        openSignInChain()
        viewModelScope.launch {
            try {
                val nonceInfoResult = loginRepo.getNonceInfo()
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

                val uuid = nonceData.uuid
                val solution = PowUtil.generateSolution(uuid ?: "", nonceData.timestamp, nonceData.version, nonceData.difficulty)
                val nonceCodeResult = loginRepo.generateNonceCode(NonceInfoRequestBody(uuid, solution))
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

                verifyInvitationCode(code)
            } catch (e: Exception) {
                L.e { "[login] signUpByNonceCode -> error: ${e.message}" }
                mSignInLiveData.value = Resource.error(NetworkException(message = e.message ?: ""))
            }
        }
    }

    




    private suspend fun verifyInvitationCode(invitationCode: String) {
        openSignInChain()
        try {
            val result = loginRepo.verifyInvitationCode(invitationCode)
            if (result.status == 0) {
                val vcode = result.data?.vcode
                val account = result.data?.account
                if (!vcode.isNullOrBlank() && !account.isNullOrBlank()) {
                    
                    signInIsRestorePath = false
                    signIn(vcode, account, Util.getSecret(16), true, null, null)
                } else {
                    L.e { "[login] verifyInvitationCode -> returned null vcode or account, vcodeIsEmpty=${vcode.isNullOrBlank()}, accountIsEmpty=${account.isNullOrBlank()}" }
                    mSignInLiveData.value = Resource.error(NetworkException(message = ResUtils.getString(com.difft.android.network.R.string.chat_net_error)))
                }
            } else {
                L.e { "[login] verifyInvitationCode -> failed with status=${result.status}, reason=${result.reason}" }
                mSignInLiveData.value = Resource.error(NetworkException(result.status, result.reason ?: ""))
            }
        } catch (e: Exception) {
            L.e { "[login] verifyInvitationCode -> error: ${e.message}" }
            mSignInLiveData.value = Resource.error(NetworkException(message = e.message ?: ""))
        }
    }

    companion object {
        const val DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE = -10010

        
        const val TOKEN_LOGIN = "logintoken"
    }

    




    @kotlinx.parcelize.Parcelize
    data class PendingSignIn(
        
        val verificationCode: String,
        val account: String,
        val email: String?,
        val phone: String?,
        



        val renewToken: String? = null,
    ) : android.os.Parcelable

    














    private fun onLoginExistingAccount(
        verificationCode: String,
        account: String,
        email: String?,
        phone: String?,
        renewToken: String? = null,
        
        
        transferOffered: Boolean = renewToken != null,
    ) {
        val pending = migrationImportLauncher.pendingRestoreSignIn()
        val localAccount = userManager.getUserData()?.account
        when (AccountSwitchPolicy.decide(pending?.account, localAccount, account)) {
            AccountSwitchPolicy.Decision.REQUIRE_REPLACEMENT -> {
                L.w { "[login] existing-account → another account's data on this device; holding the verified sign-in and asking to replace" }
                signInIsRestorePath = false
                viewModelScope.launch {
                    pendingLoginStore.save(PendingSignIn(verificationCode, account, email, phone, renewToken))
                    mSignInLiveData.value = Resource.error(NetworkException(errorCode = DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE))
                }
                return
            }
            AccountSwitchPolicy.Decision.RESUME_PENDING_RESTORE -> {
                
                
                L.i { "[login] existing-account → pending restore sign-in for this account, continuing restore path useInstalledIdentity=${pending?.useInstalledIdentity}" }
                signInIsRestorePath = true
                restoreUseInstalledIdentity = pending?.useInstalledIdentity ?: true
                signInAfterRenewIfNeeded(verificationCode, account, email, phone, renewToken)
                return
            }
            AccountSwitchPolicy.Decision.PROCEED_FRESH, AccountSwitchPolicy.Decision.PROCEED_SAME_ACCOUNT -> Unit
        }
        val entry = LoginEntryPolicy.decide(
            hasTransferContract = transferOffered,
            hasPendingRestore = false, 
            isFreshTarget = migrationImportLauncher.isFreshInstallTarget(),
            isReregistering = false, 
        )
        signInIsRestorePath = false
        when {
            entry == LoginEntryPolicy.Decision.OFFER_FRESH_INSTALL && migrationImportLauncher.isImportOffered() -> {
                L.i { "[login] existing-account → fresh install target with a transfer on offer, emitting migration prompt" }
                migrationPromptConsumed = false
                mMigrationPromptLiveData.value = PendingSignIn(verificationCode, account, email, phone, renewToken)
            }
            else -> {
                L.i { "[login] existing-account → $entry, normal signIn transferToken=${renewToken != null}" }
                signInAfterRenewIfNeeded(verificationCode, account, email, phone, renewToken)
            }
        }
    }

    






    private fun signInAfterRenewIfNeeded(
        verificationCode: String,
        account: String,
        email: String?,
        phone: String?,
        renewToken: String?,
    ) {
        if (renewToken == null) {
            signIn(verificationCode, account, Util.getSecret(16), false, email, phone)
            return
        }
        viewModelScope.launch {
            val renewed = redeemTransferToken(renewToken) ?: return@launch
            val renewedAccount = renewed.account.orEmpty()
            if (account.isNotEmpty() && !sameAccount(account, renewedAccount)) {
                L.w { "[login] renew returned a different account than the verification did — using the renewed one" }
            }
            signIn(renewed.verificationCode.orEmpty(), renewedAccount, Util.getSecret(16), false, email, phone, transferToken = renewToken)
        }
    }

    



    private suspend fun redeemTransferToken(token: String): EmailVerifyData? {
        openSignInChain()
        L.i { "[login] renew transfer token → account + code" }
        val renewed = try {
            loginRepo.renewAccount(token)
        } catch (e: Exception) {
            
            
            L.e { "[login] renew failed: ${e.message}" }
            mSignInLiveData.value = Resource.error(SignInTransportFailure(ResUtils.getString(com.difft.android.network.R.string.chat_net_error)))
            return null
        }
        val data = renewed.data
        if (renewed.status != 0 || data?.verificationCode.isNullOrEmpty() || data?.account.isNullOrEmpty()) {
            L.e { "[login] renew rejected status=${renewed.status} reason=${renewed.reason} codePresent=${!data?.verificationCode.isNullOrEmpty()} accountPresent=${!data?.account.isNullOrEmpty()}" }
            mSignInLiveData.value = Resource.error(NetworkException(renewed.status, ResUtils.getString(R.string.login_renew_failed)))
            return null
        }
        return data
    }

    





    fun continueSignInAfterPrompt(pending: PendingSignIn, restored: Boolean) {
        signInIsRestorePath = restored
        
        
        restoreUseInstalledIdentity =
            restored && (migrationImportLauncher.pendingRestoreSignIn()?.useInstalledIdentity ?: true)
        L.i { "[login] continue signIn after prompt restored=$restored useInstalledIdentity=$restoreUseInstalledIdentity transferToken=${pending.renewToken != null}" }
        signInAfterRenewIfNeeded(pending.verificationCode, pending.account, pending.email, pending.phone, pending.renewToken)
    }

    




    private fun checkIsDifferentAccountLogin(newAccount: String?): Boolean {
        val oldAccount = userManager.getUserData()?.account?.takeIf { it.isNotEmpty() }
            ?: migrationImportLauncher.pendingRestoreSignIn()?.account
        return !oldAccount.isNullOrEmpty() && !newAccount.isNullOrEmpty() && !sameAccount(oldAccount, newAccount)
    }

    private fun sameAccount(a: String, b: String): Boolean = a.isNotBlank() && a.trim() == b.trim()

    




    private fun signIn(
        verificationCode: String,
        account: String,
        password: String,
        newUser: Boolean,
        email: String?,
        phone: String?,
        
        transferToken: String? = null,
    ) {
        openSignInChain()
        if (checkIsDifferentAccountLogin(account)) {
            L.w { "[login] signIn -> different account detected" }
            mSignInLiveData.value = Resource.error(NetworkException(errorCode = DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE))
            return
        }

        
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val basicAuth = AuthCredentials(account, password).asBasic()
                val name = ""
                val signalingKey = Util.getSecret(52)
                val registrationId = getNewRegistrationId()

                
                
                
                val useInstalledIdentity = signInIsRestorePath && restoreUseInstalledIdentity

                
                val transferProof = transferProofFor(account, transferToken, useInstalledIdentity)
                val signInResult = loginRepo.signIn(verificationCode, basicAuth, signalingKey, name, registrationId, transferProof)
                if (signInResult.status != 0) {
                    L.e { "[login] signIn -> signIn API failed, status=${signInResult.status}, reason=${signInResult.reason}" }
                    throw NetworkException(signInResult.status, signInResult.reason ?: "")
                }

                
                
                
                val oldAciPriv = runCatching {
                    encryptionDataManager.get().getAciIdentityKey().privateKey.serialize()
                }.getOrNull()

                
                val preKeyResult = publishIdentityForSignIn(basicAuth, useInstalledIdentity = useInstalledIdentity)
                if (preKeyResult.status != 0) {
                    L.e { "[login] signIn -> registerPreKeys failed, status=${preKeyResult.status}, reason=${preKeyResult.reason}" }
                    throw NetworkException(preKeyResult.status, preKeyResult.reason ?: "")
                }

                
                val tokenResult = httpClient.get().httpService.fetchAuthToken(basicAuth)
                if (tokenResult.status == 0) {
                    
                    
                    
                    
                    
                    userManager.update(commit = true) {
                        this.baseAuth = basicAuth
                        this.account = account
                        this.signalingKey = signalingKey
                        this.microToken = tokenResult.data?.token
                        this.email = email
                        this.phoneNumber = phone
                        
                        
                        
                        this.migrationHandoffDoneAt = 0L
                        this.migrationHandoffId = ""
                    }
                    mSignInLiveData.postValue(Resource.success(AccountData(newUser)))

                    
                    
                    
                    try {
                        val newAciPriv = encryptionDataManager.get().getAciIdentityKey().privateKey.serialize()
                        favoriteWriteRepo.get().onPrimaryLogin(oldAciPriv, newAciPriv)
                    } catch (e: Exception) {
                        L.w { "[login] favorites key action after login failed: ${e.message}" }
                    }
                } else {
                    L.e { "[login] signIn -> fetchAuthToken failed, status=${tokenResult.status}, reason=${tokenResult.reason}" }
                    mSignInLiveData.postValue(Resource.error(NetworkException(message = tokenResult.reason ?: "")))
                }
            } catch (e: Exception) {
                L.e { "[login] signIn -> error: ${e.message}" }
                if (e !is NetworkException) {
                    
                    
                    mSignInLiveData.postValue(Resource.error(SignInTransportFailure(ResUtils.getString(com.difft.android.network.R.string.chat_net_error))))
                    return@launch
                }
                val message = when (e.errorCode) {
                    403 -> ResUtils.getString(R.string.login_error_not_exist)
                    400 -> ResUtils.getString(R.string.login_error_verification_failed)
                    413 -> ResUtils.getString(R.string.login_error_too_many_attempts)
                    else -> ResUtils.getString(com.difft.android.network.R.string.chat_net_error)
                }
                mSignInLiveData.postValue(Resource.error(NetworkException(message = message)))
            }
        }
    }

    




    @androidx.annotation.VisibleForTesting
    internal suspend fun publishIdentityForSignIn(basicAuth: String, useInstalledIdentity: Boolean): BaseResponse<Any> {
        val result = try {
            registerPreKeys(basicAuth, preferInstalledIdentity = useInstalledIdentity)
        } catch (e: Exception) {
            if (useInstalledIdentity && e.condemnsInstalledIdentity()) {
                markInstalledIdentityUnusable("threw ${e.javaClass.simpleName}: ${e.message}")
            }
            throw e
        }
        if (result.status != 0 && useInstalledIdentity) markInstalledIdentityUnusable("status=${result.status}")
        return result
    }

    
    private fun Exception.condemnsInstalledIdentity(): Boolean = when (this) {
        is NetworkException -> (errorCode ?: -1) in 400..499
        is java.io.IOException -> false
        else -> true
    }

    






    @androidx.annotation.VisibleForTesting
    internal fun transferProofFor(account: String, transferToken: String?, useInstalledIdentity: Boolean): TransferProof? {
        if (transferToken == null || !useInstalledIdentity) return null
        val edm = encryptionDataManager.get()
        if (!edm.hasAciIdentityKey()) return null
        L.i { "[login] signIn carries transferProof (identity continuation)" }
        return TransferProofSigner.build(account, edm.getAciIdentityKey(), transferToken)
    }

    private fun markInstalledIdentityUnusable(why: String) {
        L.w { "[login] publishing the installed identity failed ($why) — next restore sign-in generates a fresh one" }
        migrationImportLauncher.markRestoreIdentityPublishFailed()
    }

    private fun getNewRegistrationId(): Int {
        val registrationId = KeyHelper.generateRegistrationId(false)
        return registrationId
    }

    












    @androidx.annotation.VisibleForTesting
    internal suspend fun registerPreKeys(
        basicAuth: String,
        preferInstalledIdentity: Boolean,
    ): BaseResponse<Any> {
        val identityKeyPair = if (preferInstalledIdentity && encryptionDataManager.get().hasAciIdentityKey()) {
            L.i { "[login] using pre-installed identity (restore path), skip generate" }
            encryptionDataManager.get().getAciIdentityKey()
        } else {
            IdentityKeyUtil.generateIdentityKeyPair().also { encryptionDataManager.get().updateAciIdentityKey(it) }
        }

        val identityKey = identityKeyPair.publicKey
        val identityKeyString = Base64.encodeBytesWithoutPadding(identityKey.serialize())
        val signByteArray = identityKeyPair.privateKey.calculateSignature(identityKeyString.toByteArray())
        val identityKeySign = Base64.encodeBytesWithoutPadding(signByteArray)

        val prekeyState = PreKeyState(identityKey, identityKeySign)

        return loginRepo.registerPreKeys(basicAuth, prekeyState)
    }

    fun verifyPhone(phone: String) {
        mVerifyPhoneLiveData.value = Resource.loading()
        viewModelScope.launch {
            try {
                val result = loginRepo.verifyPhone(phone)
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

    




    fun verifyPhoneCodeWithLogin(phone: String, code: String) =
        verifyCodeWithLogin(mLoginPhoneCodeLiveData, email = null, phone = phone) {
            loginRepo.verifyPhoneCode(phone, code)
        }

    





    private fun verifyCodeWithLogin(
        codeLiveData: MutableLiveData<Resource<EmailVerifyData>>,
        email: String?,
        phone: String?,
        request: suspend () -> BaseResponse<EmailVerifyData>
    ) {
        openSignInChain()
        codeLiveData.value = Resource.loading()
        viewModelScope.launch {
            try {
                val result = request()
                if (result.status == 0) {
                    codeLiveData.value = Resource.success(result.data)
                    routeVerifiedCode(result.data, email, phone, codeLiveData)
                } else {
                    failCodeVerify(codeLiveData, NetworkException(message = result.reason ?: ""))
                }
            } catch (e: Exception) {
                failCodeVerify(codeLiveData, NetworkException(message = e.message ?: ""))
            }
        }
    }

    








    private suspend fun routeVerifiedCode(
        data: EmailVerifyData?,
        email: String?,
        phone: String?,
        codeLiveData: MutableLiveData<Resource<EmailVerifyData>>,
    ) {
        val token = data?.tokens?.get(TOKEN_LOGIN)?.takeIf { it.isNotEmpty() }
        val invitationCode = data?.invitationCode?.takeIf { it.isNotEmpty() }
        val useTransferToken = data?.transferable == 1 && token != null
        L.i {
            "[login] verification reply transferable=${data?.transferable} tokenPresent=${token != null} " +
                "codePresent=${!data?.verificationCode.isNullOrEmpty()} nextStep=${data?.nextStep} useTransferToken=$useTransferToken"
        }
        when {
            useTransferToken -> {
                val account = data?.account.orEmpty()
                if (account.isEmpty()) {
                    
                    
                    
                    L.w { "[login] transfer token without an account — redeeming it now to learn the account" }
                    redeemTransferToken(token.orEmpty())?.let {
                        onLoginExistingAccount(it.verificationCode.orEmpty(), it.account.orEmpty(), email, phone, transferOffered = true)
                    }
                } else {
                    onLoginExistingAccount("", account, email, phone, renewToken = token)
                }
            }
            data?.nextStep == 0 && invitationCode != null -> verifyInvitationCode(invitationCode)
            data?.nextStep == 0 -> {
                L.e { "[login] verification asked to register but carried no invitation code transferable=${data.transferable} tokenPresent=${token != null}" }
                failCodeVerify(codeLiveData, NetworkException(message = ResUtils.getString(com.difft.android.network.R.string.chat_net_error)))
            }
            else -> onLoginExistingAccount(data?.verificationCode ?: "", data?.account ?: "", email, phone)
        }
    }

    




    private fun failCodeVerify(
        codeLiveData: MutableLiveData<Resource<EmailVerifyData>>,
        exception: NetworkException
    ) {
        codeLiveData.value = Resource.error(exception)
        mSignInLiveData.value = Resource.error(exception)
    }

    fun verifyEmail(email: String) {
        mVerifyEmailLiveData.value = Resource.loading()
        viewModelScope.launch {
            try {
                val result = loginRepo.verifyEmail(email)
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

    


    fun verifyEmailCodeWithLogin(email: String, emailCode: String) =
        verifyCodeWithLogin(mLoginEmailCodeLiveData, email = email, phone = null) {
            loginRepo.verifyEmailCode(email, emailCode)
        }

    fun verifyAccount(account: String, countryCode: String) {
        if (ValidatorUtil.isPhone(account)) {
            verifyPhone(countryCode + account)
        } else if (ValidatorUtil.isEmail(account)) {
            verifyEmail(account)
        } else {
            
            mAccountInvalidLiveData.value = Resource.error(NetworkException(message = ResUtils.getString(R.string.login_error_invalid_account)))
        }
    }
}
