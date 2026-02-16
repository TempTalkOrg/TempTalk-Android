package com.difft.android.login

import android.app.Activity
import com.difft.android.base.log.lumberjack.L
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.SecureSharedPrefsUtil
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import com.difft.android.base.utils.dp
import com.difft.android.login.databinding.ActivityBindAccountBinding
import com.difft.android.login.repo.BindRepo
import com.difft.android.login.ui.CountryPickerActivity
import com.difft.android.base.widget.setTopMargin
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.hi.dhl.binding.viewbind
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ComposeDialog
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class BindAccountActivity : BaseActivity() {

    companion object {
        const val INTENT_EXTRA_TYPE = "INTENT_EXTRA_TYPE"

        const val TYPE_BIND_EMAIL = "TYPE_BIND_EMAIL"
        const val TYPE_CHANGE_EMAIL = "TYPE_CHANGE_EMAIL"
        const val TYPE_BIND_PHONE = "TYPE_BIND_PHONE"
        const val TYPE_CHANGE_PHONE = "TYPE_CHANGE_PHONE"

        fun startActivity(activity: Context, type: String) {
            val intent = Intent(activity, BindAccountActivity::class.java).apply {
                putExtra(INTENT_EXTRA_TYPE, type)
            }
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityBindAccountBinding by viewbind()

    @Inject
    lateinit var bindRepo: BindRepo

    private val activityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            // 绑定成功，将结果传递给调用方
            setResult(Activity.RESULT_OK)
            finish()
        } else if (it.resultCode != Activity.RESULT_CANCELED) {
            // 其他情况（如验证失败），不设置RESULT_OK
            finish()
        }
    }

    private val type: String by lazy {
        intent.getStringExtra(INTENT_EXTRA_TYPE) ?: ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
    }

    private val countryPickerActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            val code = it.data?.getStringExtra("code")
            mBinding.tvPhoneCode.text = code
        }
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }
//        mBinding.skip.setOnClickListener { showSkipDialog() }

        when (type) {
            TYPE_BIND_EMAIL -> {
                mBinding.loginTitle.text = getString(R.string.login_bind_email)
                mBinding.account.hint = getString(R.string.login_new_email)
                mBinding.account.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }

            TYPE_CHANGE_EMAIL -> {
                mBinding.loginTitle.text = getString(R.string.login_change_email)
                mBinding.account.hint = getString(R.string.login_new_email)
                mBinding.account.inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }

            TYPE_BIND_PHONE -> {
                mBinding.loginTitle.text = getString(R.string.login_bind_phone_number)
                mBinding.account.hint = getString(R.string.login_new_phone_number)
                mBinding.account.inputType = InputType.TYPE_CLASS_PHONE
                mBinding.clPhone.visibility = View.VISIBLE
                mBinding.tvPhoneCode.text = getDefaultCountryCode()
            }

            TYPE_CHANGE_PHONE -> {
                mBinding.loginTitle.text = getString(R.string.login_change_phone_number)
                mBinding.account.hint = getString(R.string.login_new_phone_number)
                mBinding.account.inputType = InputType.TYPE_CLASS_PHONE
                mBinding.clPhone.visibility = View.VISIBLE
                mBinding.tvPhoneCode.text = getDefaultCountryCode()
            }
        }

        mBinding.clPhone.setOnClickListener {
            val intent = Intent(this, CountryPickerActivity::class.java)
            countryPickerActivityLauncher.launch(intent)
        }

        disableHandleZone()
        mBinding.account.doOnTextChanged { text, _, _, _ ->
            val content = text.toString().trim()
            if (content.isEmpty()) disableHandleZone() else enableHandleZone()
        }
        mBinding.handleZone.setOnClickListener {
            verifyAccount(null)
        }
    }

    private fun getDefaultCountryCode(): String {
        val defaultRegion = Locale.getDefault().country
        val phoneNumberUtil = PhoneNumberUtil.getInstance()
        val countryCode = phoneNumberUtil.getCountryCodeForRegion(defaultRegion)
        return "+$countryCode"
    }

    private fun disableHandleZone() =
        mBinding.apply {
            handleZone.isEnabled = false
            animationView.visibility = View.GONE
            nextText.visibility = View.VISIBLE
            nextText.setTextColor(ContextCompat.getColor(this@BindAccountActivity, com.difft.android.base.R.color.t_disable))
        }

    private fun enableHandleZone() =
        mBinding.apply {
            handleZone.isEnabled = true
            animationView.visibility = View.GONE
            nextText.visibility = View.VISIBLE
            nextText.setTextColor(ContextCompat.getColor(this@BindAccountActivity, com.difft.android.base.R.color.t_white))
        }

    private fun loadingHandleZone() =
        mBinding.apply {
            handleZone.isEnabled = true
            animationView.visibility = View.VISIBLE
            nextText.visibility = View.GONE
        }

    private fun verifyAccount(nonce: String?) {
        val account = mBinding.account.text.toString().trim()
        val basicAuth = SecureSharedPrefsUtil.getBasicAuth()

        loadingHandleZone()

        lifecycleScope.launch {
            try {
                if (type == TYPE_BIND_EMAIL || type == TYPE_CHANGE_EMAIL) {
                    val result = bindRepo.verifyEmail(basicAuth, account, nonce).await()
                    enableHandleZone()
                    if (result.status == 0) {
                        val bundle = VerifyCodeActivity.createBundle(false, type, account, nonce)
                        activityLauncher.launch(Intent(this@BindAccountActivity, VerifyCodeActivity::class.java).putExtras(bundle))
                    } else {
                        if (result.status == 24) {
                            result.data?.nonce?.let { nonceValue ->
                                showAlreadyLinkedDialog(nonceValue)
                            } ?: run {
                                showInvalidView("Nonce is null")
                            }
                        } else {
                            showInvalidView(result.reason)
                        }
                    }
                } else {
                    val countryCode = mBinding.tvPhoneCode.text.toString().trim()
                    val fullAccount = countryCode + account
                    val result = bindRepo.verifyPhone(basicAuth, fullAccount, nonce).await()
                    enableHandleZone()
                    if (result.status == 0) {
                        val bundle = VerifyCodeActivity.createBundle(false, type, fullAccount, nonce)
                        activityLauncher.launch(Intent(this@BindAccountActivity, VerifyCodeActivity::class.java).putExtras(bundle))
                    } else {
                        if (result.status == 10109) {
                            result.data?.nonce?.let { nonceValue ->
                                showAlreadyLinkedDialog(nonceValue)
                            } ?: run {
                                showInvalidView("Nonce is null")
                            }
                        } else {
                            showInvalidView(result.reason)
                        }
                    }
                }
            } catch (e: Exception) {
                L.w { "[BindAccountActivity] error: ${e.stackTraceToString()}" }
                enableHandleZone()
                showInvalidView(e.message)
            }
        }
    }

    private fun showInvalidView(errorMessage: String?) {
        mBinding.clAccount.background = ResUtils.getDrawable(R.drawable.login_account_error_border)
        if (!errorMessage.isNullOrEmpty()) {
            mBinding.errorHint.text = errorMessage
            mBinding.errorHint.visibility = View.VISIBLE
            mBinding.handleZone.setTopMargin(20.dp)
        } else {
            mBinding.errorHint.visibility = View.GONE
            mBinding.handleZone.setTopMargin(16.dp)
        }
    }

    private fun showAlreadyLinkedDialog(nonce: String) {
        if (type == TYPE_BIND_EMAIL || type == TYPE_CHANGE_EMAIL) {
            ComposeDialogManager.showMessageDialog(
                context = this,
                title = getString(R.string.login_email_already_linked),
                message = getString(R.string.login_email_already_linked_tips),
                confirmText = getString(com.difft.android.chat.R.string.chat_dialog_ok),
                cancelText = getString(com.difft.android.chat.R.string.chat_dialog_cancel),
                onConfirm = {
                    verifyAccount(nonce)
                }
            )
        } else {
            ComposeDialogManager.showMessageDialog(
                context = this,
                title = getString(R.string.login_phone_number_already_linked),
                message = getString(R.string.login_phone_number_already_linked_tips),
                confirmText = getString(com.difft.android.chat.R.string.chat_dialog_ok),
                cancelText = getString(com.difft.android.chat.R.string.chat_dialog_cancel),
                onConfirm = {
                    verifyAccount(nonce)
                }
            )
        }
    }

}
