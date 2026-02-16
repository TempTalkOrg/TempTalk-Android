package com.difft.android.login

import android.app.Activity
import com.difft.android.base.log.lumberjack.L
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import com.difft.android.base.BaseActivity
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.login.databinding.ActivityVerifyCodeBinding
import com.difft.android.login.repo.BindRepo
import com.difft.android.login.viewmodel.LoginViewModel
import com.difft.android.login.viewmodel.LoginViewModel.Companion.DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.viewmodel.Status
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.widget.ComposeDialogManager
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
    }

    private val mBinding by lazy { ActivityVerifyCodeBinding.inflate(layoutInflater) }
    private val viewModel by viewModels<LoginViewModel>()
    private var fromLogin: Boolean = true
    private var type: String = ""
    private var account: String? = null
    private var nonce: String? = null
    private val handle by lazy { Handler(Looper.getMainLooper()) }
    private var timerRunnable: TimerRunnable? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromLogin = intent.getBooleanExtra(INTENT_EXTRA_FROM_LOGIN, true)
        type = intent.getStringExtra(INTENT_EXTRA_TYPE) ?: ""
        account = intent.getStringExtra(INTENT_EXTRA_ACCOUNT)
        nonce = intent.getStringExtra(INTENT_EXTRA_NONCE)
        setContentView(mBinding.root)
        initView()
        startObserve()
        startTimer()
        startAutoFillHelpers()
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
        disableHandleZone()
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
        mBinding.handleZone.setOnClickListener {
            showInvalidEmailCodeView(null)
            verifyEmailCode()
        }
        mBinding.emailCodeView.setText("")
        mBinding.emailCodeView.setOnTextChangeListener { text, isComplete ->
            if (isComplete) {
                enableHandleZone()
            } else {
                disableHandleZone()
            }
        }

        // 设置 resendEmail 的点击事件
        mBinding.resendEmail.setOnClickListener {
            if (mBinding.resendEmail.isEnabled) {
                ComposeDialogManager.showWait(this@VerifyCodeActivity, "")
                mBinding.emailCodeView.setText("")
                showInvalidEmailCodeView(null)
                if (fromLogin) {
                    account?.let {
                        if (type == TYPE_BIND_PHONE) {
                            viewModel.verifyPhone(it)
                        } else if (type == TYPE_BIND_EMAIL) {
                            viewModel.verifyEmail(it)
                        }
                    }
                } else {
                    verifyBindAccount()
                }
            }
        }
        setResendEnabled(false)
    }

    private fun disableHandleZone() =
        mBinding.apply {
            handleZone.isEnabled = false
            animationView.visibility = View.GONE
            nextText.visibility = View.VISIBLE
            nextText.setTextColor(ContextCompat.getColor(this@VerifyCodeActivity, com.difft.android.base.R.color.gray_200))
        }

    private fun enableHandleZone() =
        mBinding.apply {
            handleZone.isEnabled = true
            animationView.visibility = View.GONE
            nextText.visibility = View.VISIBLE
            nextText.setTextColor(ContextCompat.getColor(this@VerifyCodeActivity, android.R.color.white))
        }

    private fun loadingHandleZone() =
        mBinding.apply {
            handleZone.isEnabled = true
            animationView.visibility = View.VISIBLE
            nextText.visibility = View.GONE
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
            observeVerifyPhoneCodeWithLogin()
            observeVerifyEmailCodeWithLogin()
            observeSignIn()
            observeVerifyPhone()
            observeVerifyEmail()
        }
    }

    private fun LoginViewModel.observeVerifyPhoneCodeWithLogin() =
        loginPhoneCodeLiveData.observe(this@VerifyCodeActivity as LifecycleOwner) {
            when (it.status) {
                Status.LOADING -> loadingHandleZone()
                Status.SUCCESS -> enableHandleZone()
                Status.ERROR -> {
                    enableHandleZone()
                    showInvalidEmailCodeView(it.exception?.errorMsg)
                }
            }
        }

    private fun LoginViewModel.observeVerifyEmailCodeWithLogin() =
        loginEmailCodeLiveData.observe(this@VerifyCodeActivity as LifecycleOwner) {
            when (it.status) {
                Status.LOADING -> loadingHandleZone()
                Status.SUCCESS -> enableHandleZone()
                Status.ERROR -> {
                    enableHandleZone()
                    showInvalidEmailCodeView(it.exception?.errorMsg)
                }
            }
        }

    private fun LoginViewModel.observeSignIn() =
        signInLiveData.observe(this@VerifyCodeActivity as LifecycleOwner) {
            when (it.status) {
                Status.LOADING -> loadingHandleZone()
                Status.SUCCESS -> {
                    enableHandleZone()
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

                Status.ERROR -> {
                    if (it.exception?.errorCode == DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE) {
                        showDifferentAccountLoginDialog()
                    } else {
                        enableHandleZone()
                        showInvalidEmailCodeView(it.exception?.errorMsg)
                    }
                }
            }
        }

    /**
     * 重新发送手机号验证码时，监听结果
     */
    private fun LoginViewModel.observeVerifyPhone() =
        verifyPhoneLiveData.observe(this@VerifyCodeActivity as LifecycleOwner) {
            when (it.status) {
                Status.LOADING -> {}
                Status.SUCCESS -> {
                    ComposeDialogManager.dismissWait()
                    startTimer()
                }

                Status.ERROR -> {
                    ComposeDialogManager.dismissWait()
                    it.exception?.errorMsg?.let { error -> ToastUtil.show(error) }
                }
            }
        }

    /**
     * 重新发送邮箱验证码时，监听结果
     */
    private fun LoginViewModel.observeVerifyEmail() =
        verifyEmailLiveData.observe(this@VerifyCodeActivity as LifecycleOwner) {
            when (it.status) {
                Status.LOADING -> {}
                Status.SUCCESS -> {
                    ComposeDialogManager.dismissWait()
                    startTimer()
                }

                Status.ERROR -> {
                    ComposeDialogManager.dismissWait()
                    it.exception?.errorMsg?.let { error -> ToastUtil.show(error) }
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

    private fun setResendEnabled(enabled: Boolean, seconds: Int = 0) {
        mBinding.resendEmail.isEnabled = enabled
        mBinding.resendEmail.setTextColor(
            ContextCompat.getColor(this, if (enabled) com.difft.android.base.R.color.t_info else com.difft.android.base.R.color.t_disable)
        )
        mBinding.resendEmail.text = if (enabled) {
            getString(R.string.login_email_code_resend, "")
        } else {
            getString(R.string.login_email_code_resend, "(${seconds}s)")
        }
    }

    private fun startTimer() {
        val expiredTime = System.currentTimeMillis() + 60 * 1000
        timerRunnable = TimerRunnable(expiredTime)
        updateResendView(expiredTime)
        timerRunnable?.let { handle.postDelayed(it, 1000) }
    }

    private fun updateResendView(expiredTime: Long) {
        val time = expiredTime - System.currentTimeMillis()
        val second = if (time > 0) (time / 1000).toInt() else 0
        setResendEnabled(second <= 0, second)
    }

    inner class TimerRunnable(private val expiredTime: Long) : Runnable {
        override fun run() {
            updateResendView(expiredTime)
            if (System.currentTimeMillis() < expiredTime) {
                handle.postDelayed(this, 1000)
            } else {
                handle.removeCallbacks(this)
            }
        }
    }

    private fun verifyBindAccount() {
        ComposeDialogManager.showWait(this@VerifyCodeActivity, "")
        val account = account ?: return
        val basicAuth = SecureSharedPrefsUtil.getBasicAuth()

        lifecycleScope.launch {
            try {
                val result = if (type == TYPE_BIND_EMAIL || type == TYPE_CHANGE_EMAIL) {
                    bindRepo.verifyEmail(basicAuth, account, nonce).await()
                } else {
                    bindRepo.verifyPhone(basicAuth, account, nonce).await()
                }
                ComposeDialogManager.dismissWait()
                if (result.status == 0) {
                    startTimer()
                } else {
                    result.reason?.let { message -> ToastUtil.show(message) }
                }
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                L.w { "[VerifyCodeActivity] error: ${e.stackTraceToString()}" }
                e.message?.let { message -> ToastUtil.show(message) }
            }
        }
    }

    private fun verifyBindAccountCode(code: String) {
        val basicAuth = SecureSharedPrefsUtil.getBasicAuth()

        if (type == TYPE_BIND_EMAIL || type == TYPE_CHANGE_EMAIL) {
            loadingHandleZone()
            lifecycleScope.launch {
                try {
                    val result = bindRepo.verifyEmailCode(basicAuth, code, nonce).await()
                    enableHandleZone()
                    if (result.status == 0) {
                        userManager.update {
                            this.email = account
                        }
                        setResult(Activity.RESULT_OK)
                        finish()
                    } else {
                        showInvalidEmailCodeView(result.reason)
                    }
                } catch (e: Exception) {
                    L.w { "[VerifyCodeActivity] error: ${e.stackTraceToString()}" }
                    enableHandleZone()
                    showInvalidEmailCodeView(e.message)
                }
            }
        } else {
            val account = account ?: return
            loadingHandleZone()
            lifecycleScope.launch {
                try {
                    val result = bindRepo.verifyPhoneCode(basicAuth, account, code, nonce).await()
                    enableHandleZone()
                    if (result.status == 0) {
                        userManager.update {
                            this.phoneNumber = account
                        }
                        setResult(Activity.RESULT_OK)
                        finish()
                    } else {
                        showInvalidEmailCodeView(result.reason)
                    }
                } catch (e: Exception) {
                    L.w { "[VerifyCodeActivity] error: ${e.stackTraceToString()}" }
                    enableHandleZone()
                    showInvalidEmailCodeView(e.message)
                }
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

    override fun onResume() {
        super.onResume()
        // Check clipboard for codes copied while app was in background
        clipboardOtpHelper?.checkClipboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerRunnable?.let { handle.removeCallbacks(it) }
        smsRetrieverHelper?.stopListening()
        clipboardOtpHelper?.stopListening()
    }
}
