package com.difft.android.login

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.application
import com.difft.android.login.PasscodeUtil.hexStringToByteArray
import com.difft.android.login.PasscodeUtil.toHex
import com.difft.android.login.data.EmailVerifyData
import com.difft.android.login.databinding.ActivityScreenLockBinding
import com.difft.android.login.viewmodel.LoginViewModel
import com.difft.android.login.viewmodel.LoginViewModel.Companion.DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE
import com.difft.android.network.viewmodel.Status
import com.hi.dhl.binding.viewbind
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.PopTip
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import com.kongzue.dialogx.util.TextInfo
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.core.Observable
import net.yslibrary.android.keyboardvisibilityevent.KeyboardVisibilityEvent
import net.yslibrary.android.keyboardvisibilityevent.Unregistrar
import util.getParcelableExtraCompat
import org.thoughtcrime.securesms.scribbles.ImageEditorHudV2
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class ScreenLockActivity : BaseActivity() {

    companion object {
        fun startActivity() {
            val intent = Intent(application, ScreenLockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            application.startActivity(intent)
            PasscodeUtil.disableScreenLock = true
        }
    }

    private val mBinding: ActivityScreenLockBinding by viewbind()

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var logoutManager: LogoutManager

    private var keyboardVisibilityEventListener: Unregistrar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PasscodeUtil.needRecordLastUseTime = false

        initView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
            }
        })

        keyboardVisibilityEventListener = KeyboardVisibilityEvent.registerEventListener(this) {
            if (it) { //键盘弹出
                mBinding.root.post {
                    mBinding.root.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun initView() {
        val tintColor = ColorStateList.valueOf(ContextCompat.getColor(this, com.difft.android.base.R.color.login_logo_color))
        ImageViewCompat.setImageTintList(mBinding.ivLogo, tintColor)

        mBinding.etPasscode1.doAfterTextChanged {
            val passcode = it.toString().trim()
            mBinding.btnSubmit.isEnabled = passcode.length in 4..10
            if (mBinding.btnSubmit.isEnabled) {
                prepareVerifyPasscode(passcode, false)
            }
        }

        mBinding.btnSubmit.setOnClickListener {
            val passcode = mBinding.etPasscode1.text.toString().trim()
            prepareVerifyPasscode(passcode, true)
        }

        mBinding.tvForgetPasscode.setOnClickListener {
            MessageDialog.show(
                R.string.settings_forget_passcode,
                R.string.settings_forget_passcode_dialog_tips,
                android.R.string.ok
            )
        }

        checkAndShowDelayTips()
    }

    override fun onDestroy() {
        super.onDestroy()
        PasscodeUtil.needRecordLastUseTime = true
    }

    /**
     * isManualVerify 是否是手动点击按钮进行检验
     */
    private fun prepareVerifyPasscode(passcode: String, isManualVerify: Boolean) {
        val storePasscode = userManager.getUserData()?.passcode
        if (storePasscode.isNullOrEmpty()) {
            L.i { "[ScreenLockActivity] current passcode is null" }
            return
        }

        val passcodeAttempts = userManager.getUserData()?.passcodeAttempts ?: 0

        if (passcodeAttempts >= 5) {
            //如果失败次数超过限制，不进行自动校验
            if (!isManualVerify) {
                L.i { "[ScreenLockActivity] Auto verification blocked due to passcode attempts limit" }
                return
            }
            val delaySeconds = (passcodeAttempts - 4) * (passcodeAttempts - 4)
            WaitDialog.show(this@ScreenLockActivity, "").setCancelable(false)
            Observable.timer(delaySeconds.toLong(), TimeUnit.SECONDS)
                .compose(RxUtil.getSchedulerComposer())
                .to(RxUtil.autoDispose(this))
                .subscribe({
                    WaitDialog.dismiss()
                    verifyPasscode(storePasscode, passcode, true)
                }, {
                    WaitDialog.dismiss()
                    it.printStackTrace()
                })
        } else {
            verifyPasscode(storePasscode, passcode, isManualVerify)
        }
    }

    private fun verifyPasscode(storePasscode: String, passcode: String, isManualVerify: Boolean) {
        val hash = storePasscode.split(":")[0]
        val salt = storePasscode.split(":")[1]

        if (PasscodeUtil.verifyPassword(hash, salt, passcode)) {
            userManager.update {
                this.passcodeAttempts = 0
                this.lastUseTime = System.currentTimeMillis()
            }
            finish()
        } else {
            if (isManualVerify) {
                val attemptsCount = (userManager.getUserData()?.passcodeAttempts ?: 0)
                userManager.update {
                    this.passcodeAttempts = attemptsCount + 1
                }

                checkAndShowDelayTips()

                mBinding.root.postDelayed({
                    TipDialog.show(R.string.settings_passcode_error_tips)
                }, 500)
            }
        }
    }

    private fun checkAndShowDelayTips() {
        val attemptsCount = (userManager.getUserData()?.passcodeAttempts ?: 0)
        if (attemptsCount > 4) {
            val delaySeconds = (attemptsCount - 4) * (attemptsCount - 4)
            mBinding.tvErrorTips.visibility = View.VISIBLE
            mBinding.tvErrorTips.text = getString(R.string.settings_passcode_attempts_delay, delaySeconds)
        } else {
            mBinding.tvErrorTips.visibility = View.GONE
        }
    }
}