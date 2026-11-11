package com.difft.android.login

import android.app.Activity
import com.difft.android.base.widget.floatAsTopBarButton
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

        
        const val INTENT_EXTRA_PENDING_SIGN_IN = "INTENT_EXTRA_PENDING_SIGN_IN"

        
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

    
    private var codeComplete = false

    
    private val nextWait by lazy { WaitDialogHandle(this) }

    
    private val resendWait by lazy { WaitDialogHandle(this) }

    





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

    




    private var pendingSignIn: LoginViewModel.PendingSignIn? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromLogin = intent.getBooleanExtra(INTENT_EXTRA_FROM_LOGIN, true)
        type = intent.getStringExtra(INTENT_EXTRA_TYPE) ?: ""
        account = intent.getStringExtra(INTENT_EXTRA_ACCOUNT)
        nonce = intent.getStringExtra(INTENT_EXTRA_NONCE)
        
        
        pendingSignIn = savedInstanceState?.let {
            BundleCompat.getParcelable(it, STATE_PENDING_SIGN_IN, LoginViewModel.PendingSignIn::class.java)
        }
        setContentView(mBinding.root)
        initView()
        mBinding.liftCodeAboveKeyboard()
        startObserve()
        resendCountdown.start()
        startAutoFillHelpers()
        
        
        
        if (savedInstanceState == null) {
            intent.extras?.let { BundleCompat.getParcelable(it, INTENT_EXTRA_PENDING_SIGN_IN, LoginViewModel.PendingSignIn::class.java) }
                ?.let { resumed ->
                    pendingSignIn = resumed
                    when {
                        
                        
                        migrationImportLauncher.pendingRestoreSignIn() != null -> {
                            L.i { "[VerifyCodeActivity] resuming a held sign-in over a promoted restore → sign in" }
                            viewModel.continueSignInAfterPrompt(resumed, restored = true)
                        }
                        
                        
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

    


    private fun startAutoFillHelpers() {
        
        if (type == TYPE_BIND_PHONE || type == TYPE_CHANGE_PHONE) {
            
            mBinding.emailCodeView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
            mBinding.emailCodeView.setAutofillHints("smsOTPCode")

            
            smsRetrieverHelper = SmsRetrieverHelper(this) { code ->
                runOnUiThread {
                    mBinding.emailCodeView.setText(code)
                }
            }
            smsRetrieverHelper?.startListening()
        }

        
        clipboardOtpHelper = ClipboardOtpHelper(this) { code ->
            runOnUiThread {
                
                if (mBinding.emailCodeView.text.isNullOrEmpty()) {
                    mBinding.emailCodeView.setText(code)
                }
            }
        }
        clipboardOtpHelper?.startListening()
    }

    private fun initView() {
        mBinding.pageClose.floatAsTopBarButton(leading = true)
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

        mBinding.resendEmail.setOnClickListener {
            if (mBinding.resendEmail.isEnabled) {
                mBinding.emailCodeView.setText("")
                showInvalidEmailCodeView(null)
                resendCode()
            }
        }
        resendCountdown.reset()
    }

    





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
            
            
            
            observeSignIn()
            observeMigrationPrompt()
            observeVerifyPhone()
            observeVerifyEmail()
        }
    }

    




    private fun LoginViewModel.observeMigrationPrompt() =
        migrationPromptLiveData.observe(this@VerifyCodeActivity as LifecycleOwner) { pending ->
            pendingSignIn = pending
            
            
            
            nextWait.dismiss()
            enableNext()
            
            
            if (consumeMigrationPrompt()) showImportMethodChooser(pending)
        }

    





    private fun showImportMethodChooser(pending: LoginViewModel.PendingSignIn) {
        chooserLauncher.launch(migrationImportLauncher.createSourceChooserIntent(this, pending.account, pending))
    }

    private val chooserLauncher: androidx.activity.result.ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingSignIn
        if (pending == null) {
            
            
            L.w { "[VerifyCodeActivity] chooser result with null pendingSignIn → re-enabling verify, user can re-submit" }
            nextWait.dismiss()
            enableNext()
            return@registerForActivityResult
        }
        when (result.resultCode) {
            
            
            
            MigrationImportLauncher.RESULT_RESTORED -> {
                L.w { "[VerifyCodeActivity] import restored but its sign-in did not finish → asking for a fresh code" }
                nextWait.dismiss()
                
                mBinding.emailCodeView.setText("")
                enableNext()
                showInvalidEmailCodeView(getString(R.string.login_restore_sign_in_again))
            }
            
            
            MigrationImportLauncher.RESULT_SKIPPED -> viewModel.continueSignInAfterPrompt(pending, restored = false)
            else -> {
                L.w { "[VerifyCodeActivity] chooser returned ${result.resultCode} → asking again" }
                showImportMethodChooser(pending)
            }
        }
    }

    






    private fun LoginViewModel.observeSignIn() =
        signInLiveData.observe(this@VerifyCodeActivity as LifecycleOwner) {
            when (it.status) {
                Status.LOADING -> loadingNext()
                Status.SUCCESS -> {
                    nextWait.dismiss()
                    enableNext()
                    if (viewModel.signInIsRestorePath) {
                        
                        
                        
                        
                        migrationImportLauncher.markRestoreSignInComplete()
                        
                        
                        L.i { "[VerifyCodeActivity] restore-path signIn success → flag COMPLETED, restartApp (cold start promoted DB)" }
                        restartApp()
                        
                        
                        
                        return@observe
                    }
                    if (it.data?.newUser == true) {
                        ContactProfileSettingActivity.startActivity(
                            this@VerifyCodeActivity,
                            ContactProfileSettingActivity.BUNDLE_VALUE_FROM_REGISTER
                        )
                    } else {
                        
                        val intent = Intent(this@VerifyCodeActivity, activityProvider.getActivityClass(ActivityType.INDEX))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(intent)
                    }
                    finish()
                }

                Status.ERROR -> onSignInError(it.exception?.errorCode, it.exception?.errorMsg)
            }
        }

    



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

    private fun LoginViewModel.observeVerifyPhone() =
        verifyPhoneLiveData.observe(this@VerifyCodeActivity as LifecycleOwner) {
            onResendResult(it.status, it.exception?.errorMsg)
        }

    private fun LoginViewModel.observeVerifyEmail() =
        verifyEmailLiveData.observe(this@VerifyCodeActivity as LifecycleOwner) {
            onResendResult(it.status, it.exception?.errorMsg)
        }

    







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
        
        
        pendingSignIn?.let { outState.putParcelable(STATE_PENDING_SIGN_IN, it) }
    }

    override fun onResume() {
        super.onResume()
        
        clipboardOtpHelper?.checkClipboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        smsRetrieverHelper?.stopListening()
        clipboardOtpHelper?.stopListening()
    }
}
