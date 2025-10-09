package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.widget.ToastUtil
import com.difft.android.databinding.ActivitySetPasscodeBinding
import com.difft.android.login.PasscodeUtil
import com.hi.dhl.binding.viewbind
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

            mBinding.etPasscode1.clearFocus()
            mBinding.etPasscode2.requestFocus()
        }

        mBinding.btnConfirm.setOnClickListener {
            syncAndSavePasscode()
        }
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

            ToastUtil.showLong(getString(R.string.settings_passcode_success_title))
            finish()
        } else {
            ToastUtil.showLong(getString(R.string.settings_passcode_error_tips))
        }
    }
}