package com.difft.android.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.AppLockCallbackManager
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.PatternLockView
import com.difft.android.base.widget.ToastUtil
import com.difft.android.login.databinding.ActivityScreenLockBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.util.ViewUtil
import javax.inject.Inject

@AndroidEntryPoint
class ScreenLockActivity : BaseActivity() {

    companion object {
        const val EXTRA_VERIFICATION_MODE = "verification_mode"

        fun startActivity(fromActivity: Activity) {
            val intent = Intent(fromActivity, ScreenLockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            fromActivity.startActivity(intent)
        }
    }

    private val mBinding: ActivityScreenLockBinding by viewbind()

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var logoutManager: LogoutManager

    // 当前解锁模式：true = Pattern, false = Passcode
    private var isPatternMode = false

    // 是否为验证模式（用于关闭锁定时的身份验证）
    private var isVerificationMode = false

    // 倒计时相关 - Pattern 和 Passcode 分别维护
    private var patternCountdownJob: Job? = null
    private var passcodeCountdownJob: Job? = null

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 禁用横屏，强制竖屏显示
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

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

        // 检查并恢复倒计时状态
        checkAndRestoreCountdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        PasscodeUtil.needRecordLastUseTime = true
        patternCountdownJob?.cancel()
        passcodeCountdownJob?.cancel()
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
            mBinding.etPasscode1.visibility = View.GONE
            mBinding.btnSubmit.visibility = View.GONE
            mBinding.patternLockView.visibility = View.VISIBLE

            // Pattern模式下，options固定在底部50dp
            updateOptionsPosition(true)

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
            mBinding.etPasscode1.visibility = View.VISIBLE
            mBinding.btnSubmit.visibility = View.VISIBLE
            mBinding.patternLockView.visibility = View.GONE

            // Passcode模式下，options跟随btn_submit，距离20dp
            updateOptionsPosition(false)

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

        // 切换模式时，根据新模式恢复对应的倒计时状态
        updateDelayStatusForCurrentMode()
    }

    private fun updateOptionsPosition(isPatternMode: Boolean) {
        val layoutParams = mBinding.llOptions.layoutParams as ConstraintLayout.LayoutParams

        if (isPatternMode) {
            // Pattern模式：固定在底部50dp
            layoutParams.topToBottom = ConstraintLayout.LayoutParams.UNSET
            layoutParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.topMargin = 0
            layoutParams.bottomMargin = (50 * resources.displayMetrics.density).toInt()
        } else {
            // Passcode模式：跟随btn_submit，距离20dp
            layoutParams.topToBottom = mBinding.btnSubmit.id
            layoutParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            layoutParams.topMargin = (20 * resources.displayMetrics.density).toInt()
            layoutParams.bottomMargin = 0
        }

        mBinding.llOptions.layoutParams = layoutParams
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

        // 立即验证
        if (isPattern) {
            verifyPattern(actualStoredHash, inputValue)
        } else {
            verifyPasscode(actualStoredHash, inputValue, isManualVerify)
        }
    }

    private fun verifyPattern(storedPatternHash: String, patternAttempt: String) {
        val hash = storedPatternHash.split(":")[0]
        val salt = storedPatternHash.split(":")[1]
        if (PasscodeUtil.verifyPassword(hash, salt, patternAttempt)) {
            onVerificationSuccess()
            AppLockCallbackManager.notifyUnlockSuccess()
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

            // 所有错误都显示 Toast 提示
            ToastUtil.show(R.string.settings_pattern_error_tips)

            // 根据尝试次数处理
            when {
                attemptsCount + 1 == 9 -> {
                    // 第9次错误：显示警告文案
                    mBinding.tvErrorTips.visibility = View.VISIBLE
                    mBinding.tvErrorTips.text = getString(R.string.settings_too_many_attempts_warning)
                }

                attemptsCount + 1 >= 5 -> {
                    // 第5-8次错误：启动倒计时
                    val delaySeconds = (attemptsCount + 1 - 4) * (attemptsCount + 1 - 4)
                    startCountdownTimer(delaySeconds, true)
                }
            }
        }
    }


    private fun verifyPasscode(storePasscode: String, passcode: String, isManualVerify: Boolean) {
        val hash = storePasscode.split(":")[0]
        val salt = storePasscode.split(":")[1]

        if (PasscodeUtil.verifyPassword(hash, salt, passcode)) {
            onVerificationSuccess()
            AppLockCallbackManager.notifyUnlockSuccess()
        } else {
            if (isManualVerify) {
                // 清空输入框
                mBinding.etPasscode1.text?.clear()

                val attemptsCount = (userManager.getUserData()?.passcodeAttempts ?: 0)
                userManager.update {
                    this.passcodeAttempts = attemptsCount + 1
                }

                // 第10次错误直接退出
                if (attemptsCount + 1 >= 10) {
                    logoutManager.doLogoutWithoutRemoveData()
                    return
                }

                // 所有错误都显示 Toast 提示
                ToastUtil.show(R.string.settings_passcode_error_tips)

                // 根据尝试次数处理
                when {
                    attemptsCount + 1 == 9 -> {
                        // 第9次错误：显示警告文案
                        mBinding.tvErrorTips.visibility = View.VISIBLE
                        mBinding.tvErrorTips.text = getString(R.string.settings_too_many_attempts_warning)
                    }

                    attemptsCount + 1 >= 5 -> {
                        // 第5-8次错误：启动倒计时
                        val delaySeconds = (attemptsCount + 1 - 4) * (attemptsCount + 1 - 4)
                        startCountdownTimer(delaySeconds, false)
                    }
                }
            }
        }
    }

    private fun startCountdownTimer(delaySeconds: Int, isPattern: Boolean) {
        // 根据模式选择对应的 Job
        if (isPattern) {
            // 取消之前的 Pattern 倒计时
            patternCountdownJob?.cancel()
            // 禁用 Pattern 输入
            mBinding.patternLockView.setInputEnabled(false)
        } else {
            // 取消之前的 Passcode 倒计时
            passcodeCountdownJob?.cancel()
            // 禁用 Passcode 输入
            mBinding.etPasscode1.isEnabled = false
            mBinding.btnSubmit.isEnabled = false
        }

        // 启动倒计时
        val job = lifecycleScope.launch {
            var remainingSeconds = delaySeconds
            while (remainingSeconds > 0) {
                // 只有当前模式与倒计时模式匹配时才更新UI
                if (isPattern == isPatternMode) {
                    mBinding.tvErrorTips.visibility = View.VISIBLE
                    updateCountdownText(remainingSeconds)
                }

                delay(1000L)
                remainingSeconds--
            }

            // 倒计时结束，恢复输入
            if (isPattern) {
                mBinding.patternLockView.setInputEnabled(true)
            } else {
                mBinding.etPasscode1.isEnabled = true
                // 清除输入框内容
                mBinding.etPasscode1.text?.clear()
                // btnSubmit 的状态由 etPasscode1 的文本变化控制，会自动更新为 false
            }

            // 只有当前模式与倒计时模式匹配时才隐藏错误提示
            if (isPattern == isPatternMode) {
                mBinding.tvErrorTips.visibility = View.INVISIBLE
            }
        }

        // 保存对应的 Job
        if (isPattern) {
            patternCountdownJob = job
        } else {
            passcodeCountdownJob = job
        }
    }

    private fun updateCountdownText(remainingSeconds: Int) {
        if (remainingSeconds > 0) {
            mBinding.tvErrorTips.text = getString(R.string.settings_yelling_unavailable_countdown, remainingSeconds)
        } else {
            mBinding.tvErrorTips.visibility = View.INVISIBLE
        }
    }

    private fun checkAndRestoreCountdown() {
        val userData = userManager.getUserData() ?: return

        // 分别检查并启动两种模式的倒计时（如果需要的话）
        // Pattern 模式
        val patternAttempts = userData.patternAttempts
        if (patternAttempts in 5..8) {
            val delaySeconds = (patternAttempts - 4) * (patternAttempts - 4)
            startCountdownTimer(delaySeconds, true)
        }

        // Passcode 模式
        val passcodeAttempts = userData.passcodeAttempts
        if (passcodeAttempts in 5..8) {
            val delaySeconds = (passcodeAttempts - 4) * (passcodeAttempts - 4)
            startCountdownTimer(delaySeconds, false)
        }

        // 根据当前显示的模式，更新UI显示
        updateDelayStatusForCurrentMode()
    }

    private fun updateDelayStatusForCurrentMode() {
        val userData = userManager.getUserData() ?: return

        // 根据当前模式获取对应的尝试次数
        val attemptsCount = if (isPatternMode) {
            userData.patternAttempts
        } else {
            userData.passcodeAttempts
        }

        // 处理第9次错误的情况
        if (attemptsCount == 9) {
            mBinding.tvErrorTips.visibility = View.VISIBLE
            mBinding.tvErrorTips.text = getString(R.string.settings_too_many_attempts_warning)
            return
        }

        // 如果在倒计时范围（5-8次），先隐藏错误提示，Job 会在下一秒更新显示
        // 否则隐藏错误提示
        mBinding.tvErrorTips.visibility = View.INVISIBLE
    }
}