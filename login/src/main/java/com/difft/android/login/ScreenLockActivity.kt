package com.difft.android.login

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.PatternLockView
import com.difft.android.base.widget.ToastUtil
import com.difft.android.login.databinding.ActivityScreenLockBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.yslibrary.android.keyboardvisibilityevent.KeyboardVisibilityEvent
import net.yslibrary.android.keyboardvisibilityevent.Unregistrar
import org.thoughtcrime.securesms.util.ViewUtil
import javax.inject.Inject

@AndroidEntryPoint
class ScreenLockActivity : BaseActivity() {

    companion object {
        const val EXTRA_VERIFICATION_MODE = "verification_mode"

        fun startActivity(fromActivity: Activity) {
            val intent = Intent(fromActivity, ScreenLockActivity::class.java)
            fromActivity.startActivity(intent)
            PasscodeUtil.disableScreenLock = true
        }
    }

    private val mBinding: ActivityScreenLockBinding by viewbind()

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var logoutManager: LogoutManager

    private var keyboardVisibilityEventListener: Unregistrar? = null

    // 当前解锁模式：true = Pattern, false = Passcode
    private var isPatternMode = false

    // 是否为验证模式（用于关闭锁定时的身份验证）
    private var isVerificationMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PasscodeUtil.needRecordLastUseTime = false

        // 检查是否为验证模式
        isVerificationMode = intent.getBooleanExtra(EXTRA_VERIFICATION_MODE, false)

        initView()

        // 根据模式设置返回按键行为
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isVerificationMode) {
                    // 验证模式允许返回
                    finish()
                }
                // 正常解锁模式不允许返回（保持原有逻辑）
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

        // 验证模式下显示返回按钮
        if (isVerificationMode) {
            mBinding.ibBack.visibility = View.VISIBLE
            mBinding.ibBack.setOnClickListener {
                finish()
            }
        } else {
            mBinding.ibBack.visibility = View.GONE
        }

        // 确定默认显示模式
        determineDefaultMode()

        // 设置模式切换点击事件
        mBinding.tvSwitchMode.setOnClickListener {
            switchMode()
        }

        // Passcode模式的事件
        mBinding.etPasscode1.doAfterTextChanged {
            val passcode = it.toString().trim()
            mBinding.btnSubmit.isEnabled = passcode.length in 4..10
            if (mBinding.btnSubmit.isEnabled) {
                prepareVerify(false, passcode, false)
            }
        }

        mBinding.btnSubmit.setOnClickListener {
            val passcode = mBinding.etPasscode1.text.toString().trim()
            prepareVerify(false, passcode, true)
        }

        // Pattern模式的事件
        setupPatternView()

        mBinding.tvForgetPasscode.setOnClickListener {
            ComposeDialogManager.showMessageDialog(
                context = this,
                title = getString(R.string.settings_forget_passcode_or_pattern),
                message = getString(R.string.settings_forget_passcode_dialog_tips),
                confirmText = getString(R.string.settings_forget_passcode_dialog_logout),
                confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(this, com.difft.android.base.R.color.t_error)),
                onConfirm = {
                    logoutManager.doLogoutWithoutRemoveData()
                },
            )
        }

        checkAndShowDelayTips(false) // Passcode
        checkAndShowDelayTips(true)  // Pattern
    }

    override fun onDestroy() {
        super.onDestroy()
        PasscodeUtil.needRecordLastUseTime = true
    }

    private fun determineDefaultMode() {
        val userData = userManager.getUserData()
        val hasPasscode = !userData?.passcode.isNullOrEmpty()
        val hasPattern = !userData?.pattern.isNullOrEmpty()

        // 如果只设置了passcode，默认显示passcode；否则优先显示pattern
        isPatternMode = hasPattern

        updateUI()
    }

    private fun switchMode() {
        isPatternMode = !isPatternMode
        updateUI()
    }

    private fun updateUI() {
        val userData = userManager.getUserData()
        val hasPasscode = !userData?.passcode.isNullOrEmpty()
        val hasPattern = !userData?.pattern.isNullOrEmpty()

        if (isPatternMode) {
            // 显示Pattern模式
            mBinding.tvTitle.text = getString(R.string.settings_draw_pattern)
            mBinding.llPasscode.visibility = View.GONE
            mBinding.patternLockView.visibility = View.VISIBLE
            mBinding.btnSubmit.visibility = View.GONE

            // 清除输入框焦点，隐藏键盘
            mBinding.etPasscode1.clearFocus()
            ViewUtil.hideKeyboard(this, mBinding.etPasscode1)

            // 显示切换到passcode的选项
            if (hasPasscode) {
                mBinding.tvSwitchMode.text = getString(R.string.settings_switch_to_passcode)
                mBinding.tvSwitchMode.visibility = View.VISIBLE
                mBinding.vSeparator.visibility = View.VISIBLE
            } else {
                mBinding.tvSwitchMode.visibility = View.GONE
                mBinding.vSeparator.visibility = View.GONE
            }
        } else {
            // 显示Passcode模式
            mBinding.tvTitle.text = getString(R.string.settings_enter_passcode)
            mBinding.llPasscode.visibility = View.VISIBLE
            mBinding.patternLockView.visibility = View.GONE
            mBinding.btnSubmit.visibility = View.VISIBLE

            // 请求输入框焦点，显示键盘
            mBinding.etPasscode1.requestFocus()
            ViewUtil.focusAndShowKeyboard(mBinding.etPasscode1)

            // 显示切换到pattern的选项
            if (hasPattern) {
                mBinding.tvSwitchMode.text = getString(R.string.settings_switch_to_pattern)
                mBinding.tvSwitchMode.visibility = View.VISIBLE
                mBinding.vSeparator.visibility = View.VISIBLE
            } else {
                mBinding.tvSwitchMode.visibility = View.GONE
                mBinding.vSeparator.visibility = View.GONE
            }
        }

        // 清除输入和错误
        mBinding.etPasscode1.text?.clear()
        mBinding.patternLockView.clearPattern()
        mBinding.tvErrorTips.visibility = View.INVISIBLE

        // 根据当前模式显示相应的延迟提示
        checkAndShowDelayTips(isPatternMode)
    }

    private fun setupPatternView() {
        val userData = userManager.getUserData()
        val showPath = userData?.patternShowPath ?: true

        mBinding.patternLockView.setInStealthMode(!showPath)

        mBinding.patternLockView.addPatternLockListener(object : PatternLockView.PatternLockViewListener {
            override fun onStarted() {
//                mBinding.tvErrorTips.visibility = View.INVISIBLE
                mBinding.patternLockView.setViewMode(PatternLockView.PatternViewMode.CORRECT)
            }

            override fun onProgress(progressPattern: MutableList<PatternLockView.Dot>) {
                // Pattern drawing in progress
            }

            override fun onComplete(pattern: MutableList<PatternLockView.Dot>) {
                val userData = userManager.getUserData()
                val storedPatternHash = userData?.pattern

                if (storedPatternHash != null) {
                    val patternString = pattern.joinToString(",") { it.id.toString() }
                    L.d { "patternString:${patternString}" }
                    prepareVerify(true, patternString, true)
                }
            }

            override fun onCleared() {
//                mBinding.tvErrorTips.visibility = View.INVISIBLE
            }
        })
    }

    private fun onVerificationSuccess() {
        userManager.update {
            this.passcodeAttempts = 0
            this.patternAttempts = 0
            this.lastUseTime = System.currentTimeMillis()
        }

        if (isVerificationMode) {
            // 验证模式下，返回成功结果
            setResult(RESULT_OK)
        }

        finish()
    }

    private fun checkAndShowDelayTips(isPattern: Boolean = false) {
        val attemptsCount = if (isPattern) {
            userManager.getUserData()?.patternAttempts ?: 0
        } else {
            userManager.getUserData()?.passcodeAttempts ?: 0
        }

        when {
            attemptsCount >= 9 -> {
                // 第9次错误后显示强制退出警告
                mBinding.tvErrorTips.visibility = View.VISIBLE
                mBinding.tvErrorTips.text = getString(R.string.settings_too_many_attempts_warning)
            }

            attemptsCount > 4 -> {
                // 5-8次错误显示延迟提示
                val delaySeconds = (attemptsCount - 4) * (attemptsCount - 4)
                mBinding.tvErrorTips.visibility = View.VISIBLE
                mBinding.tvErrorTips.text = getString(R.string.settings_passcode_attempts_delay, delaySeconds)
            }

            else -> {
                mBinding.tvErrorTips.visibility = View.INVISIBLE
            }
        }
    }

    private fun prepareVerify(isPattern: Boolean, inputValue: String, isManualVerify: Boolean) {
        // 获取存储的hash
        val actualStoredHash = if (isPattern) {
            val storePattern = userManager.getUserData()?.pattern
            if (storePattern.isNullOrEmpty()) {
                L.i { "[ScreenLockActivity] current pattern is null" }
                return
            }
            storePattern
        } else {
            val storePasscode = userManager.getUserData()?.passcode
            if (storePasscode.isNullOrEmpty()) {
                L.i { "[ScreenLockActivity] current passcode is null" }
                return
            }
            storePasscode
        }

        // 获取尝试次数
        val attempts = if (isPattern) {
            userManager.getUserData()?.patternAttempts ?: 0
        } else {
            userManager.getUserData()?.passcodeAttempts ?: 0
        }


        // 对于Passcode，如果失败次数超过限制且不是手动验证，则不进行自动校验
        if (!isPattern && attempts >= 5 && !isManualVerify) {
            L.i { "[ScreenLockActivity] Auto verification blocked due to passcode attempts limit" }
            return
        }

        if (attempts >= 5) {
            // 第9次错误后，第10次输入时直接验证不延迟
            if (attempts >= 9) {
                // 直接验证，不延迟
                if (isPattern) {
                    verifyPattern(actualStoredHash, inputValue)
                } else {
                    verifyPasscode(actualStoredHash, inputValue, isManualVerify)
                }
            } else {
                // 5-8次错误需要延迟等待
                val delaySeconds = (attempts - 4) * (attempts - 4)
                ComposeDialogManager.showWait(this@ScreenLockActivity, "", cancelable = false)

                lifecycleScope.launch {
                    try {
                        delay(delaySeconds * 1000L)
                        ComposeDialogManager.dismissWait()
                        if (isPattern) {
                            verifyPattern(actualStoredHash, inputValue)
                        } else {
                            verifyPasscode(actualStoredHash, inputValue, true)
                        }
                    } catch (e: Exception) {
                        ComposeDialogManager.dismissWait()
                        e.printStackTrace()
                    }
                }
            }
        } else {
            // 1-4次错误直接验证
            if (isPattern) {
                verifyPattern(actualStoredHash, inputValue)
            } else {
                verifyPasscode(actualStoredHash, inputValue, isManualVerify)
            }
        }
    }

    private fun verifyPattern(storedPatternHash: String, patternAttempt: String) {
        val hash = storedPatternHash.split(":")[0]
        val salt = storedPatternHash.split(":")[1]
        if (PasscodeUtil.verifyPassword(hash, salt, patternAttempt)) {
            onVerificationSuccess()
        } else {
            // Pattern incorrect
            mBinding.patternLockView.setViewMode(PatternLockView.PatternViewMode.WRONG)

            // Clear pattern after delay
            mBinding.patternLockView.postDelayed({
                mBinding.patternLockView.clearPattern()
                mBinding.patternLockView.setViewMode(PatternLockView.PatternViewMode.CORRECT)
            }, 1000)

            val attemptsCount = (userManager.getUserData()?.patternAttempts ?: 0)
            userManager.update {
                this.patternAttempts = attemptsCount + 1
            }

            // 第10次错误直接退出
            if (attemptsCount + 1 >= 10) {
                logoutManager.doLogoutWithoutRemoveData()
                return
            }

            // 检查并显示延迟提示
            checkAndShowDelayTips(true)

            ToastUtil.show(R.string.settings_pattern_error_tips)
        }
    }


    private fun verifyPasscode(storePasscode: String, passcode: String, isManualVerify: Boolean) {
        val hash = storePasscode.split(":")[0]
        val salt = storePasscode.split(":")[1]

        if (PasscodeUtil.verifyPassword(hash, salt, passcode)) {
            onVerificationSuccess()
        } else {
            if (isManualVerify) {
                val attemptsCount = (userManager.getUserData()?.passcodeAttempts ?: 0)
                userManager.update {
                    this.passcodeAttempts = attemptsCount + 1
                }

                // 第10次错误直接退出
                if (attemptsCount + 1 >= 10) {
                    logoutManager.doLogoutWithoutRemoveData()
                    return
                }

                checkAndShowDelayTips(false)

                ToastUtil.show(R.string.settings_passcode_error_tips)
            }
        }
    }
}