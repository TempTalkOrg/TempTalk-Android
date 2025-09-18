package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.databinding.ActivitySetPasscodeBinding
import com.difft.android.login.PasscodeUtil
import com.difft.android.setting.repo.SettingRepo
import com.hi.dhl.binding.viewbind
import com.kongzue.dialogx.dialogs.PopTip
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.util.ViewUtil
import javax.inject.Inject

@AndroidEntryPoint
class SetPasscodeActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, SetPasscodeActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivitySetPasscodeBinding by viewbind()

    @Inject
    lateinit var userManager: UserManager


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mBinding.llStep1.isVisible) {
                    finish()
                } else if (mBinding.llStep2.isVisible) {
                    mBinding.llStep1.visibility = View.VISIBLE
                    mBinding.llStep2.visibility = View.GONE
                    mBinding.llSuccess.visibility = View.GONE

                    mBinding.etPasscode1.requestFocus()
                    mBinding.etPasscode2.clearFocus()
                    ViewUtil.focusAndShowKeyboard(mBinding.etPasscode1)
                }
            }
        })
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        mBinding.tvPasscodeTips1.text = getString(R.string.settings_passcode_tips, PackageUtil.getAppName())

        mBinding.etPasscode1.doAfterTextChanged {
            val passcode = it.toString().trim()
            mBinding.btnNext.isEnabled = passcode.length in 4..10
        }

        mBinding.etPasscode2.doAfterTextChanged {
            val passcode = it.toString().trim()
            mBinding.btnConfirm.isEnabled = passcode.length in 4..10
        }

        mBinding.btnNext.setOnClickListener {
            mBinding.llStep1.visibility = View.GONE
            mBinding.llStep2.visibility = View.VISIBLE
            mBinding.llSuccess.visibility = View.GONE

            mBinding.etPasscode1.clearFocus()
            mBinding.etPasscode2.requestFocus()
        }

        mBinding.btnConfirm.setOnClickListener {
            syncAndSavePasscode()
        }

        mBinding.tvPasscodeSuccessTips.text = createPasscodeSuccessSpannableText()

        mBinding.btnGotIt.setOnClickListener {
            finish()
        }
    }

    private fun createPasscodeSuccessSpannableText(): SpannableString {
        val successTips = getString(R.string.settings_passcode_success_tips)
        val forgetTips = getString(R.string.settings_passcode_forget_tips)
        val fullText = successTips + forgetTips

        val spannableString = SpannableString(fullText)

        // Apply different color to the first part (success tips)
        spannableString.setSpan(
            ForegroundColorSpan(getColor(com.difft.android.base.R.color.t_secondary)),
            0,
            successTips.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Apply different color and bold to the second part (forget tips)
        spannableString.setSpan(
            ForegroundColorSpan(getColor(com.difft.android.base.R.color.t_primary)),
            successTips.length,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannableString.setSpan(
            StyleSpan(Typeface.BOLD),
            successTips.length,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        return spannableString
    }

    private fun syncAndSavePasscode() {
        val passcode1 = mBinding.etPasscode1.text.toString().trim()
        val passcode2 = mBinding.etPasscode2.text.toString().trim()
        if (passcode1 == passcode2) {
            val (salt, hashedPassword) = PasscodeUtil.createSaltAndHashByPassword(passcode1)
            val passcode = "${hashedPassword}:${salt}"
            val timeout = userManager.getUserData()?.passcodeTimeout ?: PasscodeUtil.DEFAULT_TIMEOUT

            userManager.update {
                this.passcode = passcode
                this.passcodeTimeout = timeout
            }

            PasscodeUtil.disableScreenLock = true

            mBinding.clTitle.visibility = View.GONE
            mBinding.tvScreenLock.visibility = View.GONE
            mBinding.llStep1.visibility = View.GONE
            mBinding.llStep2.visibility = View.GONE
            mBinding.llSuccess.visibility = View.VISIBLE
        } else {
            TipDialog.show(getString(R.string.settings_passcode_error_tips))
        }
    }
}