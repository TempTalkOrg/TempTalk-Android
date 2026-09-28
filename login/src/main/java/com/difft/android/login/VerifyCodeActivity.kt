package com.difft.android.login

import android.app.Activity
import com.difft.android.base.log.lumberjack.L
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.difft.android.base.BaseActivity
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ResUtils
import com.difft.android.login.databinding.ActivityVerifyCodeBinding
import com.difft.android.login.repo.BindRepo
import com.difft.android.login.viewmodel.LoginViewModel
import com.difft.android.login.viewmodel.LoginViewModel.Companion.DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.viewmodel.Status
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.migration.MigrationImportLauncher
import com.difft.android.base.utils.restartApp
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.WaitDialogHandle
import com.difft.android.base.widget.withWait
import com.difft.android.login.sms.ClipboardOtpHelper
import com.difft.android.login.sms.SmsRetrieverHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil

@AndroidEntryPoint
class VerifyCodeActivity : BaseActivity() {

    companion object {
        private const val INTENT_EXTRA_FROM_LOGIN = "INTENT_EXTRA_FROM_LOGIN"
        const val INTENT_EXTRA_TYPE = "INTENT_EXTRA_TYPE"
        const val INTENT_EXTRA_ACCOUNT = "INTENT_EXTRA_ACCOUNT"
        const val INTENT_EXTRA_NONCE = "INTENT_EXTRA_NONCE"

        /** A sign-in verified before another account's data was wiped; the chooser opens at once. */
        const val INTENT_EXTRA_PENDING_SIGN_IN = "INTENT_EXTRA_PENDING_SIGN_IN"

        /** Saved-state key persisting [pendingSignIn] across Activity recreation. */
        private const val STATE_PENDING_SIGN_IN = "STATE_PENDING_SIGN_IN"

        const val TYPE_BIND_EMAIL = "TYPE_BIND_EMAIL"
        const val TYPE_CHANGE_EMAIL = "TYPE_CHANGE_EMAIL"
        const val TYPE_BIND_PHONE = "TYPE_BIND_PHONE"
        const val TYPE_CHANGE_PHONE = "TYPE_CHANGE_PHONE"

        fun createBundle(fromLogin: Boolean, type: String, account: String?, nonce: String? = null): Bundle {
            return Bundle().apply {
                putBoolean(INTENT_EXTRA_FROM_LOGIN, fromLogin)
                putString(INTENT_EXTRA_TYPE, type)
                putString(INTENT_EXTRA_ACCOUNT, account)
                putString(INTENT_EXTRA_NONCE, nonce)
            }
        }

        /** Opens this page on an already-verified sign-in, straight into the migration chooser. */
        fun createResumeBundle(pending: LoginViewModel.PendingSignIn): Bundle =
            createBundle(
                fromLogin = true,
                type = if (pending.phone != null) TYPE_BIND_PHONE else TYPE_BIND_EMAIL,
                account = pending.phone ?: pending.email,
            ).apply { putParcelable(INTENT_EXTRA_PENDING_SIGN_IN, pending) }
    }

    private val mBinding by lazy { ActivityVerifyCodeBinding.inflate(layoutInflater) }
    private val viewModel by viewModels<LoginViewModel>()
    private var fromLogin: Boolean = true
    private var type: String = ""
    private var account: String? = null
    private var nonce: String? = null

    /** Whether the code input holds a complete code — the only owner of the next button's state. */
    private var codeComplete = false

    /** The next button's flow: code verification and the sign-in chain it runs into. */
    private val nextWait by lazy { WaitDialogHandle(this) }

    /** The resend link's flow, kept apart so its outcome cannot release the next button's dialog. */
    private val resendWait by lazy { WaitDialogHandle(this) }

    /**
     * Whether a resend is awaiting its outcome, so only a resend still in flight restarts the
     * countdown. The resend LiveData are sticky and the loading state is what raises the dialog, so
     * a replay after a rotation legitimately re-arms this and re-raises the dialog: the request it
     * belongs to may well still be running, and its outcome then lands on this screen.
     */
    private var resendInFlight = false
    private val resendCountdown by lazy { ResendCountdown(mBinding.resendEmail, lifecycleScope) }
    private var smsRetrieverHelper: SmsRetrieverHelper? = null
    private var clipboardOtpHelper: ClipboardOtpHelper? = null

    @Inject
    lateinit var userManager: UserManager

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    @Inject
    lateinit var bindRepo: BindRepo

    @Inject
    lateinit var logoutManager: LogoutManager

    @Inject
    lateinit var activityProvider: ActivityProvider

    @Inject
    lateinit var migrationImportLauncher: MigrationImportLauncher

    /**
     * The captured verify arguments (account/vcode) for the in-flight migration prompt. Held while
     * the chooser and the import Activity are open, so every resolution continues sign-in with the
     * same values via [LoginViewModel.continueSignInAfterPrompt].
     */
    private var pendingSignIn: LoginViewModel.PendingSignIn? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromLogin = intent.getBooleanExtra(INTENT_EXTRA_FROM_LOGIN, true)
        type = intent.getStringExtra(INTENT_EXTRA_TYPE) ?: ""
        account = intent.getStringExtra(INTENT_EXTRA_ACCOUNT)
        nonce = intent.getStringExtra(INTENT_EXTRA_NONCE)
        // Restore the in-flight migration sign-in args across recreation (process death, config
        // change) so the import-result callback can still continue sign-in.
        pendingSignIn = savedInstanceState?.let {
            BundleCompat.getParcelable(it, STATE_PENDING_SIGN_IN, LoginViewModel.PendingSignIn::class.java)
        }
        setContentView(mBinding.root)
        initView()
        startObserve()
        resendCountdown.start()
        startAutoFillHelpers()
        // A sign-in held in `PendingLoginStore` across the wipe of another account's data: already
        // verified with the token unredeemed, so the chooser opens at once and no second code is
        // asked for. Fresh instances only — a recreation replays the prompt instead.
        if (savedInstanceState == null) {
            intent.extras?.let { BundleCompat.getParcelable(it, INTENT_EXTRA_PENDING_SIGN_IN, LoginViewModel.PendingSignIn::class.java) }
                ?.let { resumed ->
                    pendingSignIn = resumed
                    when {
                        // The import finished but the process died before the sign-in: finish it now,
                        // on the restore path — the data is on disk and must not be imported again.
                        migrationImportLauncher.pendingRestoreSignIn() != null -> {
                            L.i { "[VerifyCodeActivity] resuming a held sign-in over a promoted restore → sign in" }
                            viewModel.continueSignInAfterPrompt(resumed, restored = true)
                        }
                        // Same decision the verification itself makes: only a transfer on offer (a token)
                        // means there is another device to import from. A plain code signs in directly.
                        resumed.renewToken != null -> {
                            L.i { "[VerifyCodeActivity] resuming a held sign-in → migration chooser" }
                            showImportMethodChooser(resumed)
                        }
                        else -> {
                            L.i { "[VerifyCodeActivity] resuming a held sign-in with no transfer on offer → sign in" }
                            viewModel.continueSignInAfterPrompt(resumed, restored = false)
                        }
                    }
                }
        }
    }

    /**
     * Start auto-fill helpers based on verification type
     */
    private fun startAutoFillHelpers() {
        // Phone verification: enable SMS Retriever + system autofill hint
        if (type == TYPE_BIND_PHONE || type == TYPE_CHANGE_PHONE) {
            // Set autofill hints for phone verification only
            mBinding.emailCodeView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
            mBinding.emailCodeView.setAutofillHints("smsOTPCode")

            // SMS Retriever for GMS devices with proper SMS format
            smsRetrieverHelper = SmsRetrieverHelper(this) { code ->
                runOnUiThread {
                    mBinding.emailCodeView.setText(code)
                }
            }
            smsRetrieverHelper?.startListening()
        }

        // Clipboard monitoring for all types (email and phone)
        clipboardOtpHelper = ClipboardOtpHelper(this) { code ->
            runOnUiThread {
                // Only fill if input is empty (avoid overwriting existing input)
                if (mBinding.emailCodeView.text.isNullOrEmpty()) {
                    mBinding.emailCodeView.setText(code)
                }
            }
        }
        clipboardOtpHelper?.startListening()
    }

    private fun initView() {
        mBinding.pageClose.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        disableNext()
        if (fromLogin) {
            mBinding.loginTitle.text = ResUtils.getString(R.string.login_sign_to_chative)
            mBinding.loginHint.text = ResUtils.getString(R.string.login_otp_sent)
        } else {
            when (type) {
                TYPE_BIND_EMAIL -> {
                    mBinding.loginTitle.text = getString(R.string.login_bind_email)
                }

                TYPE_CHANGE_EMAIL -> {
                    mBinding.loginTitle.text = getString(R.string.login_change_email)
                }

                TYPE_BIND_PHONE -> {
                    mBinding.loginTitle.text = getString(R.string.login_bind_phone_number)
                }

                TYPE_CHANGE_PHONE -> {
                    mBinding.loginTitle.text = getString(R.string.login_change_phone_number)
                }
            }
            mBinding.loginHint.text = ResUtils.getString(R.string.login_otp_sent_to, account)
        }
        mBinding.btnNext.setOnClickListener {
            showInvalidEmailCodeView(null)
            verifyEmailCode()
        }
        mBinding.emailCodeView.setText("")
        mBinding.emailCodeView.setOnTextChangeListener { _, isComplete ->
            if (isComplete) {
                codeComplete = true
                enableNext()
            } else {
                disableNext()
            }
        }

        // 设置 resendEmail 的点击事件
        mBinding.resendEmail.setOnClickListener {
            if (mBinding.resendEmail.isEnabled) {
                mBinding.emailCodeView.setText("")
                showInvalidEmailCodeView(null)
                resendCode()
            }
        }
        resendCountdown.reset()
    }

    /**
     * The login resend goes through the view model, whose LiveData raise and release the dialog in
     * [onResendResult]; the bind resend raises and releases its own dialog inside
     * [verifyBindAccount]. Only one of them may raise a dialog, and a path that bails out here
     * dispatches nothing — a non-cancelable modal with no request behind it would lock the screen.
     */
    private fun resendCode() {
        if (!fromLogin) {
            verifyBindAccount()
            return
        }
        val target = account ?: return
        if (type != TYPE_BIND_PHONE && type != TYPE_BIND_EMAIL) return
        if (type == TYPE_BIND_PHONE) viewModel.verifyPhone(target) else viewModel.verifyEmail(target)
    }

    private fun disableNext() {
        codeComplete = false
        mBinding.btnNext.isEnabled = false
    }

    /**
     * The button returns to whatever the code input says, never to a forced `true` — input
     * validity stays its only owner, and this does not touch the wait dialog.
     */
    private fun enableNext() {
        mBinding.btnNext.isEnabled = codeComplete
    }

    private fun loadingNext() {
        nextWait.show()
    }

    private fun verifyEmailCode() {
        val code = mBinding.emailCodeView.text.toString()
        if (fromLogin) {
            account?.let {
                if (type == TYPE_BIND_PHONE) {
                    viewModel.verifyPhoneCodeWithLogin(it, code)
                } else if (type == TYPE_BIND_EMAIL) {
                    viewModel.verifyEmailCodeWithLogin(it, code)
                }
            }
        } else {
            verifyBindAccountCode(code)
        }
    }

    private fun startObserve() {
        viewModel.apply {
            // Registration order matters: both streams are sticky and a recreated Activity replays
            // them in order. The prompt must be observed after the sign-in chain, or the replayed
            // loading state re-raises the wait dialog on top of the chooser the prompt just opened.
            observeSignIn()
            observeMigrationPrompt()
            observeVerifyPhone()
            observeVerifyEmail()
        }
    }

    /**
     * At `nextStep == 1` with the import flag on, the ViewModel emits a prompt event instead of
     * signing in, and this opens the chooser. The only exit to normal-login-without-import is the
     * chooser's own skip button.
     */
    private fun LoginViewModel.observeMigrationPrompt() =
        migrationPromptLiveData.observe(this@VerifyCodeActivity as LifecycleOwner) { pending ->
            pendingSignIn = pending
            // Close the sign-in chain's wait dialog here: [observeSignIn] releases it only on SUCCESS
            // or ERROR, and this branch reaches neither — it holds sign-in and hands control to the
            // user. Re-entering the chain via [continueSignInAfterPrompt] raises the dialog again.
            nextWait.dismiss()
            enableNext()
            // Once per prompt: a replay into a recreated Activity still releases the dialog above,
            // but the chooser it opened the first time is already on top.
            if (consumeMigrationPrompt()) showImportMethodChooser(pending)
        }

    /**
     * The source chooser, a full page launched for result. It opens the pairing page itself and stays
     * underneath it, so this screen is not re-shown between the two. It therefore answers only for
     * the whole import: a restore that still owes a sign-in, or the skip confirmed inside the page.
     * Any other result asks again rather than signing in.
     */
    private fun showImportMethodChooser(pending: LoginViewModel.PendingSignIn) {
        chooserLauncher.launch(migrationImportLauncher.createSourceChooserIntent(this, pending.account, pending))
    }

    private val chooserLauncher: androidx.activity.result.ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingSignIn
        if (pending == null) {
            // pendingSignIn should survive recreation via onSaveInstanceState; if it is genuinely
            // null, re-enable the verify input so the user can re-submit instead of dead-ending.
            L.w { "[VerifyCodeActivity] chooser result with null pendingSignIn → re-enabling verify, user can re-submit" }
            nextWait.dismiss()
            enableNext()
            return@registerForActivityResult
        }
        when (result.resultCode) {
            // The pairing page finishes the sign-in itself and gives this back only when that failed.
            // The token may be spent, so no automatic retry: a fresh code is verified, and the
            // promoted restore on disk makes that verification register directly.
            MigrationImportLauncher.RESULT_RESTORED -> {
                L.w { "[VerifyCodeActivity] import restored but its sign-in did not finish → asking for a fresh code" }
                nextWait.dismiss()
                // The old code is spent; clearing it also drops Next until a full new one is typed.
                mBinding.emailCodeView.setText("")
                enableNext()
                showInvalidEmailCodeView(getString(R.string.login_restore_sign_in_again))
            }
            // Confirmed inside the chooser: continue the normal sign-in. It is the one exit to
            // normal-login-without-import, so an accidental close cannot slip into a sign-in.
            MigrationImportLauncher.RESULT_SKIPPED -> viewModel.continueSignInAfterPrompt(pending, restored = false)
            else -> {
                L.w { "[VerifyCodeActivity] chooser returned ${result.resultCode} → asking again" }
                showImportMethodChooser(pending)
            }
        }
    }

    /**
     * `signInLiveData` is the whole next-button chain's single state source: the view model raises
     * it before the code request goes out and lands every failure of both legs on it, so one
     * observer owns the dialog, the button and the error text. Two observers driving them would
     * cross-talk — these are sticky LiveData, so a replay of one leg's outcome would close a modal
     * the other leg had just raised.
     */
    private fun LoginViewModel.observeSignIn() =
        signInLiveData.observe(this@VerifyCodeActivity as LifecycleOwner) {
            when (it.status) {
                Status.LOADING -> loadingNext()
                Status.SUCCESS -> {
                    nextWait.dismiss()
                    enableNext()
                    if (viewModel.signInIsRestorePath) {
                        // Sign-in that continued a successful restore. Move the flag off SIGNING_IN
                        // first — left there it would force the restore path on every future login —
                        // and onto COMPLETED, which the cold start below turns into the one-shot
                        // success page. The flag write is synchronous so it lands before restartApp().
                        migrationImportLauncher.markRestoreSignInComplete()
                        // The DB was promoted on disk; the lazy WCDB singleton may already hold the old
                        // empty DB, so a cold start is mandatory — restart the process, don't navigate.
                        L.i { "[VerifyCodeActivity] restore-path signIn success → flag COMPLETED, restartApp (cold start promoted DB)" }
                        restartApp()
                        // Explicit: restartApp() kills the process synchronously, but without this
                        // return the restore path would also run the navigation block below should
                        // the restart ever become async.
                        return@observe
                    }
                    if (it.data?.newUser == true) {
                        //新用户，跳转至设置用户信息页面
                        ContactProfileSettingActivity.startActivity(
                            this@VerifyCodeActivity,
                            ContactProfileSettingActivity.BUNDLE_VALUE_FROM_REGISTER
                        )
                    } else {
                        // 使用Intent标志清除整个Activity栈，确保登录流程的所有Activity都被关闭
                        val intent = Intent(this@VerifyCodeActivity, activityProvider.getActivityClass(ActivityType.INDEX))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(intent)
                    }
                    finish()
                }

                Status.ERROR -> onSignInError(it.exception?.errorCode, it.exception?.errorMsg)
            }
        }

    /**
     * Release the wait dialog BEFORE anything else, on both branches: it is a non-cancelable modal
     * window, so a message dialog raised behind it leaves the user unable to answer it or leave.
     */
    @VisibleForTesting
    internal fun onSignInError(errorCode: Int?, errorMsg: String?) {
        nextWait.dismiss()
        enableNext()
        if (errorCode == DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE) {
            showDifferentAccountLoginDialog()
        } else {
            showInvalidEmailCodeView(errorMsg)
        }
    }

    /**
     * 重新发送手机号验证码时，监听结果
     */
    private fun LoginViewModel.observeVerifyPhone() =
        verifyPhoneLiveData.observe(this@VerifyCodeActivity as LifecycleOwner) {
            onResendResult(it.status, it.exception?.errorMsg)
        }

    /**
     * 重新发送邮箱验证码时，监听结果
     */
    private fun LoginViewModel.observeVerifyEmail() =
        verifyEmailLiveData.observe(this@VerifyCodeActivity as LifecycleOwner) {
            onResendResult(it.status, it.exception?.errorMsg)
        }

    /**
     * The resend flow's whole state: the loading state raises its own dialog and arms
     * [resendInFlight], and an outcome releases that dialog alone. These are sticky LiveData, so
     * every value can arrive as a replay — a replayed loading state re-raises the dialog and
     * re-arms the flag, which is correct, while a replayed outcome must neither close the next
     * button's modal (the token keeps it apart) nor restart a countdown for a resend that is no
     * longer in flight.
     */
    private fun onResendResult(status: Status, errorMsg: String?) {
        when (status) {
            Status.LOADING -> {
                resendInFlight = true
                resendWait.show()
            }

            Status.SUCCESS -> {
                resendWait.dismiss()
                if (resendInFlight) {
                    resendInFlight = false
                    resendCountdown.start()
                }
            }

            Status.ERROR -> {
                resendWait.dismiss()
                resendInFlight = false
                errorMsg?.let { error -> ToastUtil.show(error) }
            }
        }
    }

    private fun showInvalidEmailCodeView(errorMessage: String?) {
        if (!errorMessage.isNullOrEmpty()) {
            mBinding.errorHint.text = errorMessage
            mBinding.errorHint.visibility = View.VISIBLE
        } else {
            mBinding.errorHint.visibility = View.GONE
        }
    }

    private fun verifyBindAccount() {
        // Before withWait, never after: a bail-out under the dialog would leave a non-cancelable
        // modal with nothing left to release it.
        val account = account ?: return

        lifecycleScope.launch {
            try {
                val result = withWait {
                    if (type == TYPE_BIND_EMAIL || type == TYPE_CHANGE_EMAIL) {
                        bindRepo.verifyEmail(account, nonce)
                    } else {
                        bindRepo.verifyPhone(account, nonce)
                    }
                }
                if (result.status == 0) {
                    resendCountdown.start()
                } else {
                    result.reason?.let { message -> ToastUtil.show(message) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[VerifyCodeActivity] error: ${e.stackTraceToString()}" }
                e.message?.let { message -> ToastUtil.show(message) }
            }
        }
    }

    private fun verifyBindAccountCode(code: String) {
        val isEmail = type == TYPE_BIND_EMAIL || type == TYPE_CHANGE_EMAIL
        // The phone request carries the account, so a missing one bails out here — before any
        // dialog. The email request identifies the account by nonce and needs none.
        val verify: suspend () -> BaseResponse<Any> = if (isEmail) {
            { bindRepo.verifyEmailCode(code, nonce) }
        } else {
            val phone = account ?: return
            { bindRepo.verifyPhoneCode(phone, code, nonce) }
        }

        lifecycleScope.launch {
            try {
                val result = withWait { verify() }
                if (result.status == 0) {
                    userManager.update {
                        if (isEmail) this.email = account else this.phoneNumber = account
                    }
                    setResult(Activity.RESULT_OK)
                    // Outside withWait, so the dialog is already released before the finish.
                    finish()
                } else {
                    showInvalidEmailCodeView(result.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[VerifyCodeActivity] error: ${e.stackTraceToString()}" }
                showInvalidEmailCodeView(e.message)
            }
        }
    }

    /**
     * 显示不同账号登录提示框
     */
    private fun showDifferentAccountLoginDialog() {
        ComposeDialogManager.showMessageDialog(
            context = this,
            title = getString(R.string.login_different_dialog_title),
            message = getString(R.string.login_different_dialog_content),
            confirmText = getString(R.string.login_different_dialog_ok_text),
            cancelText = getString(R.string.login_different_dialog_cancel_text),
            cancelable = false,
            confirmButtonColor = androidx.compose.ui.graphics.Color(
                ContextCompat.getColor(this@VerifyCodeActivity, com.difft.android.base.R.color.t_error)
            ),
            onConfirm = {
                logoutManager.doLogout()
            },
            onCancel = {
                finish()
            }
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Persist the in-flight migration sign-in args so a recreated Activity (process death while
        // the import sub-activity is foreground) can still continue sign-in on result.
        pendingSignIn?.let { outState.putParcelable(STATE_PENDING_SIGN_IN, it) }
    }

    override fun onResume() {
        super.onResume()
        // Check clipboard for codes copied while app was in background
        clipboardOtpHelper?.checkClipboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        smsRetrieverHelper?.stopListening()
        clipboardOtpHelper?.stopListening()
    }
}
