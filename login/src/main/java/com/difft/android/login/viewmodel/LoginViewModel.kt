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

    /** Where a verified sign-in waits while another account's data is wiped — see [PendingLoginStore]. */
    @Inject
    lateinit var pendingLoginStore: PendingLoginStore

    /**
     * Migration prompt event, emitted instead of calling `signIn` at `nextStep == 1` when an import
     * is on offer. Carries the exact `signIn(...)` arguments so the same verified account/code can
     * continue sign-in later, whether the user imports or skips.
     */
    private val mMigrationPromptLiveData = MutableLiveData<PendingSignIn>()
    internal val migrationPromptLiveData: LiveData<PendingSignIn> = mMigrationPromptLiveData

    private var migrationPromptConsumed = false

    /**
     * True once per emitted prompt. The LiveData is sticky: a recreated Activity replays it while the
     * chooser it already opened is still on top, and must not open a second one.
     */
    internal fun consumeMigrationPrompt(): Boolean {
        if (migrationPromptConsumed) return false
        migrationPromptConsumed = true
        return true
    }

    /**
     * Set while the in-flight `signIn` continues a successful restore. On success the observer must
     * `restartApp()` — the promoted DB needs a cold start — rather than navigate. Reset to false for
     * every normal sign-in.
     */
    @Volatile
    var signInIsRestorePath: Boolean = false
        private set

    /**
     * On the restore path: publish the identity the restore installed (true), or generate a fresh one
     * because publishing the installed one already failed once (false). Meaningless off the restore
     * path. Read from the pending-restore record at every restore-path entry, never carried over.
     */
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

    /**
     * Opens [signInLiveData] with a loading state; the screen raises its non-cancelable modal on it.
     * Every leg of a chain calls this regardless of what ran before, and the write must stay
     * unconditional: the chain can end parked on LOADING (the migration prompt hands control to the
     * user, so the screen drops the modal while the stream still reads LOADING), and a de-duplicated
     * write would then leave the next leg running with no modal at all. Re-emitting costs nothing —
     * observers call an idempotent `show()` on their wait handle.
     */
    private fun openSignInChain() {
        mSignInLiveData.value = Resource.loading()
    }

    /**
     * 一键注册
     */
    fun signUpByNonceCode() {
        openSignInChain()
        viewModelScope.launch {
            try {
                // Step 1: 获取 nonce info
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

                // Step 2: 生成 nonce code
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

                // Step 3: 验证邀请码
                verifyInvitationCode(code)
            } catch (e: Exception) {
                L.e { "[login] signUpByNonceCode -> error: ${e.message}" }
                mSignInLiveData.value = Resource.error(NetworkException(message = e.message ?: ""))
            }
        }
    }

    /**
     * Verify a temporary invitation code (one-click-registration nonce code or the
     * server-issued registration token from email/phone signup). Long-term personal
     * invite codes are no longer accepted by the server on this endpoint.
     */
    private suspend fun verifyInvitationCode(invitationCode: String) {
        openSignInChain()
        try {
            val result = loginRepo.verifyInvitationCode(invitationCode)
            if (result.status == 0) {
                val vcode = result.data?.vcode
                val account = result.data?.account
                if (!vcode.isNullOrBlank() && !account.isNullOrBlank()) {
                    // Register (nextStep==0) is a brand-new account — never a restore continuation.
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

        /** Key of the transfer (renew) token inside `EmailVerifyData.tokens`. */
        const val TOKEN_LOGIN = "logintoken"
    }

    /**
     * The exact arguments needed to (re)invoke [signIn] for an existing-account login. Captured at
     * `nextStep == 1` so the same verified account/code drives sign-in whether the user imports
     * first or skips; the import branch never re-verifies.
     */
    @kotlinx.parcelize.Parcelize
    data class PendingSignIn(
        /** Empty on the transfer-token path: the code is only minted by [LoginRepo.renewAccount]. */
        val verificationCode: String,
        val account: String,
        val email: String?,
        val phone: String?,
        /**
         * The `supportTransfer` token. While it is unredeemed the account's current device keeps its
         * session; redeeming it (after the import, or on skip) is what revokes that device.
         */
        val renewToken: String? = null,
    ) : android.os.Parcelable

    /**
     * The single decision point for an existing account, reached by both [verifyPhoneCodeWithLogin]
     * and [verifyEmailCodeWithLogin]. Two decisions, in the order iOS makes them:
     *
     * 1. [AccountSwitchPolicy] — what to do about the account already on this device. Decided before
     *    the transfer token is redeemed, because redeeming it signs the account's other device out:
     *    another account's data here means asking the user to wipe it first, with the verified
     *    sign-in held in [PendingLoginStore] so the fresh start needs no second code. A promoted
     *    restore continues without a prompt; the same account coming back keeps its data.
     * 2. [LoginEntryPolicy] — whether to offer an import: only when the server offered a transfer
     *    (a token came back, so the account has another device) and only on a fresh install.
     *
     * Every branch that signs in goes through [signInAfterRenewIfNeeded]; only the offer branch
     * holds the sign-in and emits [mMigrationPromptLiveData].
     */
    private fun onLoginExistingAccount(
        verificationCode: String,
        account: String,
        email: String?,
        phone: String?,
        renewToken: String? = null,
        // The server offered a transfer (the account has another device). Normally the same as having
        // a token; differs only when the token had to be redeemed early to learn the account.
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
                // Promotion already done in a prior attempt; only sign-in remains. Continue on the
                // restore path (restartApp on success) without re-prompting or re-importing.
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
            hasPendingRestore = false, // decided above
            isFreshTarget = migrationImportLauncher.isFreshInstallTarget(),
            isReregistering = false, // Android has no separate re-registration state
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

    /**
     * Sign in, first redeeming the transfer token when there is one. `PUT v1/accounts/renew/` hands
     * back the account and the one-time code that the plain path already had; from there the two
     * paths are identical. A renew that fails (expired or already redeemed token, network) surfaces
     * as a sign-in error: the user requests a new code, and a promoted import on disk is picked up
     * by the pending-restore branch of the next verification.
     */
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

    /**
     * `PUT v1/accounts/renew/`: the transfer token becomes the account + one-time code. Null means the
     * failure has already been posted to [signInLiveData]; the caller just stops.
     */
    private suspend fun redeemTransferToken(token: String): EmailVerifyData? {
        openSignInChain()
        L.i { "[login] renew transfer token → account + code" }
        val renewed = try {
            loginRepo.renewAccount(token)
        } catch (e: Exception) {
            // The request did not get an answer, so the token is unspent: this is the ordinary network
            // failure every other request reports, not an expired sign-in — the caller may retry it.
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

    /**
     * Continue sign-in after the migration prompt resolves.
     *
     * @param restored true when a restore succeeded (data promoted to disk), which marks the restore
     *   path so the observer restarts the app on success; false continues the normal sign-in.
     */
    fun continueSignInAfterPrompt(pending: PendingSignIn, restored: Boolean) {
        signInIsRestorePath = restored
        // A restore that just completed installed its identity moments ago; only a recorded earlier
        // publish failure (a resumed attempt) turns this off.
        restoreUseInstalledIdentity =
            restored && (migrationImportLauncher.pendingRestoreSignIn()?.useInstalledIdentity ?: true)
        L.i { "[login] continue signIn after prompt restored=$restored useInstalledIdentity=$restoreUseInstalledIdentity transferToken=${pending.renewToken != null}" }
        signInAfterRenewIfNeeded(pending.verificationCode, pending.account, pending.email, pending.phone, pending.renewToken)
    }

    /**
     * 检查是否是不同账号登录
     *
     * The account on file is written at sign-in step 3, so a restore that promoted data but never
     * finished its sign-in leaves it empty; the pending-restore record then names the account that
     * data belongs to. Either source makes a different account's login a rejected one.
     */
    private fun checkIsDifferentAccountLogin(newAccount: String?): Boolean {
        val oldAccount = userManager.getUserData()?.account?.takeIf { it.isNotEmpty() }
            ?: migrationImportLauncher.pendingRestoreSignIn()?.account
        return !oldAccount.isNullOrEmpty() && !newAccount.isNullOrEmpty() && !sameAccount(oldAccount, newAccount)
    }

    private fun sameAccount(a: String, b: String): Boolean = a.isNotBlank() && a.trim() == b.trim()

    /**
     * 使用账号登录
     *
     * [openSignInChain] rather than a plain loading write: this leg opens the stream itself, and an
     * earlier leg having already opened it costs nothing. Every exit writes a terminal state,
     * including the guard below.
     */
    private fun signIn(
        verificationCode: String,
        account: String,
        password: String,
        newUser: Boolean,
        email: String?,
        phone: String?,
        // The `supportTransfer` token this sign-in came through, if any: what a transferProof is bound to.
        transferToken: String? = null,
    ) {
        openSignInChain()
        if (checkIsDifferentAccountLogin(account)) {
            L.w { "[login] signIn -> different account detected" }
            mSignInLiveData.value = Resource.error(NetworkException(errorCode = DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE))
            return
        }

        // Default: libsignal keygen/signature is CPU-bound. LiveData updates use postValue().
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // 准备登录参数
                val basicAuth = AuthCredentials(account, password).asBasic()
                val name = ""
                val signalingKey = Util.getSecret(52)
                val registrationId = getNewRegistrationId()

                // Identity continuity: on a restore continuation a migration or recovery has already
                // installed the original identity; the sign-in proves it and step 2 publishes it
                // rather than generating a replacement. False off the restore path.
                val useInstalledIdentity = signInIsRestorePath && restoreUseInstalledIdentity

                // Step 1: Sign in
                val transferProof = transferProofFor(account, transferToken, useInstalledIdentity)
                val signInResult = loginRepo.signIn(verificationCode, basicAuth, signalingKey, name, registrationId, transferProof)
                if (signInResult.status != 0) {
                    L.e { "[login] signIn -> signIn API failed, status=${signInResult.status}, reason=${signInResult.reason}" }
                    throw NetworkException(signInResult.status, signInResult.reason ?: "")
                }

                // Capture the previous aci identity private key before registerPreKeys overwrites it:
                // if it survived (passive re-login) the favorites list can be preserved via rewrap, if
                // it is gone the list must be reset. Unrecoverable once the new identity is written.
                val oldAciPriv = runCatching {
                    encryptionDataManager.get().getAciIdentityKey().privateKey.serialize()
                }.getOrNull()

                // Step 2: Register pre keys (publishing the installed identity on the restore path).
                val preKeyResult = publishIdentityForSignIn(basicAuth, useInstalledIdentity = useInstalledIdentity)
                if (preKeyResult.status != 0) {
                    L.e { "[login] signIn -> registerPreKeys failed, status=${preKeyResult.status}, reason=${preKeyResult.reason}" }
                    throw NetworkException(preKeyResult.status, preKeyResult.reason ?: "")
                }

                // Step 3: Fetch auth token
                val tokenResult = httpClient.get().httpService.fetchAuthToken(basicAuth)
                if (tokenResult.status == 0) {
                    // Persist the credentials durably before reporting success: the default
                    // (commit = false) write is fire-and-forget on appScope, and the restore path
                    // restarts the process the instant it observes success, which would leave only
                    // `secure_user.pb.tmp` behind and bring the cold start up unauthenticated. Safe
                    // to block here — signIn runs on Dispatchers.Default, never the main thread.
                    userManager.update(commit = true) {
                        this.baseAuth = basicAuth
                        this.account = account
                        this.signalingKey = signalingKey
                        this.microToken = tokenResult.data?.token
                        this.email = email
                        this.phoneNumber = phone
                        // A fresh sign-in supersedes any earlier same-device hand-over: without this the
                        // done marker would route the next logged-out launch to the "moved" page and the
                        // cold-start reconciler would sign this new session out again.
                        this.migrationHandoffDoneAt = 0L
                        this.migrationHandoffId = ""
                    }
                    mSignInLiveData.postValue(Resource.success(AccountData(newUser)))

                    // Favorites key action after primary login: the new aci identity is in place, so
                    // decide rewrap (old key survived) vs reset (old key gone). Best-effort — it must
                    // never fail the login itself.
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
                    // The HTTP layer never turned this into a server verdict, so the request got no
                    // answer and the held sign-in is unspent: a retry can still succeed.
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

    /**
     * Sign-in step 2. When the installed identity is refused by the server or fails to decode, that
     * is recorded so the next attempt generates a fresh one. A transport failure proves nothing about
     * the key and keeps it for the retry.
     */
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

    /** True when the failure is about the key (server 4xx or a local decode error), not the link. */
    private fun Exception.condemnsInstalledIdentity(): Boolean = when (this) {
        is NetworkException -> (errorCode ?: -1) in 400..499
        is java.io.IOException -> false
        else -> true
    }

    /**
     * The `transferProof` for this sign-in, or null when there is nothing to prove. Only the restore
     * continuation that will publish the installed identity ([useInstalledIdentity]) holds the
     * account's current key, and only a sign-in that came through a transfer token has the token the
     * server bound the proof to. With the proof the server keeps the account's secondary devices
     * signed in instead of revoking them; without it the sign-in is the old flow.
     */
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

    /**
     * Publish this device's identity to the server (`PUT v2/keys`) — the single install-vs-generate
     * fork shared by device migration and key cold recovery. Both install the account's original
     * identity into secure storage before sign-in reaches this step, and this step publishes that
     * identity rather than generating a new one: `masterKey == the ACI identity private key` and the
     * server holds only the public half, so regenerating is irreversible — every still-queued message
     * sealed to the original identity becomes undecryptable and the identity-derived key chain
     * (favourites, profile, group key redistribution) resets. Republishing the same public key is
     * idempotent server-side, so the install path is safe to retry.
     *
     * @param preferInstalledIdentity true only on the restore continuation; falls back to generating
     *   when no identity is present locally.
     */
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

    /**
     * 登录时验证手机号验证码
     *
     * Code verification and the sign-in it runs into are one operation from the screen's side, so
     * [signInLiveData] carries the whole chain: loading before the code request goes out, and a
     * terminal state on every path out of either leg. See [verifyCodeWithLogin].
     */
    fun verifyPhoneCodeWithLogin(phone: String, code: String) =
        verifyCodeWithLogin(mLoginPhoneCodeLiveData, email = null, phone = phone) {
            loginRepo.verifyPhoneCode(phone, code)
        }

    /**
     * The Next-button chain both code entry points run: [signInLiveData] opens with loading, the
     * code leg settles on [codeLiveData] as well, and a verified code hands straight over to the
     * account it belongs to — the invitation leg when the server asks for one, [signIn] otherwise.
     * [email] and [phone] are what that sign-in stores for the account, so exactly one is non-null.
     */
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

    /**
     * Where a verified code goes — the same selection iOS makes on the same reply. A transfer token
     * (`transferable == 1` with a `logintoken`) wins over any code beside it: the code would register
     * this device at once and revoke the account's other one, which is exactly what the token exists
     * to defer. Without a token the reply is the plain contract: `nextStep == 0` with an invitation
     * code registers, anything else is an existing account signing in with the code it was given.
     * The transfer reply also says `nextStep == 0` and carries no invitation code, so it must be read
     * before `nextStep` is.
     */
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
                    // An older server hands out the token without the account. The account is needed
                    // before anything can be decided, so the token is redeemed now — which revokes
                    // the other device early; current servers always send the account.
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

    /**
     * A code verification that fails ends the chain here, so the failure has to reach
     * [signInLiveData] as well — otherwise that stream stays on the loading state this chain put
     * it in. [codeLiveData] keeps its own write so any observer of that leg alone still works.
     */
    private fun failCodeVerify(
        codeLiveData: MutableLiveData<Resource<EmailVerifyData>>,
        exception: NetworkException
    ) {
        codeLiveData.value = Resource.error(exception)
        mSignInLiveData.value = Resource.error(exception)
    }

    /**
     * 使用邮箱登录时，发送验证码
     */
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

    /**
     * 登录时验证邮箱验证码
     *
     * Same contract as [verifyPhoneCodeWithLogin]: [signInLiveData] carries the whole chain.
     */
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
            // Only email/phone can log in; invite-code login was removed for security.
            mAccountInvalidLiveData.value = Resource.error(NetworkException(message = ResUtils.getString(R.string.login_error_invalid_account)))
        }
    }
}
