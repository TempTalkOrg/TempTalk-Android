package com.difft.android.login

import android.app.Activity
import com.difft.android.base.widget.floatAsTopBarButton
import android.content.Intent
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
import com.difft.android.base.activity.AppUnlockListener
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.AppLockCallbackManager
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.liftAboveKeyboard
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.PatternLockView
import com.difft.android.base.widget.ToastUtil
import com.difft.android.login.databinding.ActivityScreenLockBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.difft.android.chat.util.ViewUtil
import util.PendingScreenLockDeeplink
import util.ScreenLockUtil
import javax.inject.Inject

@AndroidEntryPoint
class ScreenLockActivity : BaseActivity() {

    companion object {
        const val EXTRA_VERIFICATION_MODE = "verification_mode"
        private const val SHORT_ENTRY_HINT_MS = 2000L

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

    
    private var isPatternMode = false

    
    private var isVerificationMode = false

    
    private var patternCountdownJob: Job? = null
    private var passcodeCountdownJob: Job? = null

    
    
    private var shortEntryHintJob: Job? = null
    private var defaultErrorTipsColors: ColorStateList? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecurityResultNonce.resultData(intent)?.let { setResult(RESULT_CANCELED, it) }
        PasscodeUtil.needRecordLastUseTime = false

        isVerificationMode = intent.getBooleanExtra(EXTRA_VERIFICATION_MODE, false)

        initView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isVerificationMode) {
                    
                    finish()
                }
                
            }
        })
    }

    private fun initView() {
        val tintColor = ColorStateList.valueOf(ContextCompat.getColor(this, com.difft.android.base.R.color.login_logo_color))
        ImageViewCompat.setImageTintList(mBinding.ivLogo, tintColor)

        mBinding.ibBack.floatAsTopBarButton(leading = true)
        if (isVerificationMode) {
            mBinding.ibBack.visibility = View.VISIBLE
            mBinding.ibBack.setOnClickListener {
                finish()
            }
        } else {
            mBinding.ibBack.visibility = View.GONE
        }

        determineDefaultMode()

        mBinding.tvSwitchMode.setOnClickListener {
            switchMode()
        }

        mBinding.etPasscode1.doAfterTextChanged {
            val passcode = it.toString().trim()
            mBinding.btnSubmit.isEnabled = passcode.length in 4..10
            if (mBinding.btnSubmit.isEnabled) {
                prepareVerify(passcode, false)
            }
        }

        mBinding.btnSubmit.setOnClickListener {
            val passcode = mBinding.etPasscode1.text.toString().trim()
            prepareVerify(passcode, true)
        }

        setupPatternView()
        ScreenLockLayoutPolicy(mBinding).attach()
        
        mBinding.root.liftAboveKeyboard(field = { mBinding.etPasscode1 }) { mBinding.btnSubmit }

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

        checkAndRestoreCountdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        PasscodeUtil.needRecordLastUseTime = true
        patternCountdownJob?.cancel()
        passcodeCountdownJob?.cancel()
        shortEntryHintJob?.cancel()
    }

    private fun determineDefaultMode() {
        val userData = userManager.getUserData()
        val hasPasscode = !userData?.passcode.isNullOrEmpty()
        val hasPattern = !userData?.pattern.isNullOrEmpty()

        
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
            mBinding.tvTitle.text = getString(R.string.settings_draw_pattern)
            mBinding.etPasscode1.visibility = View.GONE
            mBinding.btnSubmit.visibility = View.GONE
            mBinding.patternLockView.visibility = View.VISIBLE

            
            updateOptionsPosition(true)

            
            mBinding.etPasscode1.clearFocus()
            ViewUtil.hideKeyboard(this, mBinding.etPasscode1)

            if (hasPasscode) {
                mBinding.tvSwitchMode.text = getString(R.string.settings_switch_to_passcode)
                mBinding.tvSwitchMode.visibility = View.VISIBLE
                mBinding.vSeparator.visibility = View.VISIBLE
            } else {
                mBinding.tvSwitchMode.visibility = View.GONE
                mBinding.vSeparator.visibility = View.GONE
            }
        } else {
            mBinding.tvTitle.text = getString(R.string.settings_enter_passcode)
            mBinding.etPasscode1.visibility = View.VISIBLE
            mBinding.btnSubmit.visibility = View.VISIBLE
            mBinding.patternLockView.visibility = View.GONE

            
            updateOptionsPosition(false)

            mBinding.etPasscode1.requestFocus()
            ViewUtil.focusAndShowKeyboard(mBinding.etPasscode1)

            if (hasPattern) {
                mBinding.tvSwitchMode.text = getString(R.string.settings_switch_to_pattern)
                mBinding.tvSwitchMode.visibility = View.VISIBLE
                mBinding.vSeparator.visibility = View.VISIBLE
            } else {
                mBinding.tvSwitchMode.visibility = View.GONE
                mBinding.vSeparator.visibility = View.GONE
            }
        }

        mBinding.etPasscode1.text?.clear()
        mBinding.patternLockView.clearPattern()

        
        updateDelayStatusForCurrentMode()
    }

    private fun updateOptionsPosition(isPatternMode: Boolean) {
        val layoutParams = mBinding.llOptions.layoutParams as ConstraintLayout.LayoutParams

        if (isPatternMode) {
            
            layoutParams.topToBottom = ConstraintLayout.LayoutParams.UNSET
            layoutParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.topMargin = 0
            layoutParams.bottomMargin = resources.getDimensionPixelSize(R.dimen.screen_lock_options_bottom_margin)
        } else {
            
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
        defaultErrorTipsColors = mBinding.tvErrorTips.textColors

        mBinding.patternLockView.addPatternLockListener(object : PatternLockView.PatternLockViewListener {
            override fun onStarted() {
                endShortEntryHint()
                mBinding.patternLockView.setViewMode(PatternLockView.PatternViewMode.CORRECT)
            }

            override fun onProgress(progressPattern: MutableList<PatternLockView.Dot>) {
                
            }

            override fun onComplete(pattern: MutableList<PatternLockView.Dot>) {
                val newSequence = pattern.map { it.id }
                val legacySequence = mBinding.patternLockView.legacyPattern.map { it.id }
                
                val credential = userManager.getUserData()?.pattern?.takeIf { it.isNotEmpty() }?.let { PatternCredential.parse(it) }
                if (credential == null) {
                    L.w { "[ScreenLockActivity] stored pattern missing or unreadable" }
                    mBinding.patternLockView.clearPattern()
                    return
                }
                val length = PatternUnlockPolicy.entryLength(newSequence, legacySequence, credential.createdWithNewRule)
                when (PatternUnlockPolicy.classify(length)) {
                    
                    PatternUnlockPolicy.EntryKind.IGNORED -> mBinding.patternLockView.clearPattern()
                    PatternUnlockPolicy.EntryKind.TOO_SHORT -> {
                        mBinding.patternLockView.clearPattern()
                        showShortEntryHint()
                    }

                    PatternUnlockPolicy.EntryKind.VERIFY -> verifyPattern(credential, newSequence, legacySequence)
                }
            }

            override fun onCleared() {
                
            }
        })
    }

    private fun showShortEntryHint() {
        shortEntryHintJob?.cancel()
        mBinding.tvErrorTips.setTextColor(ContextCompat.getColor(this, com.difft.android.base.R.color.t_secondary))
        mBinding.tvErrorTips.text = getString(R.string.settings_pattern_min_dots_hint)
        mBinding.tvErrorTips.visibility = View.VISIBLE
        shortEntryHintJob = lifecycleScope.launch {
            delay(SHORT_ENTRY_HINT_MS)
            updateDelayStatusForCurrentMode()
        }
    }

    
    private fun endShortEntryHint() {
        if (shortEntryHintJob != null) updateDelayStatusForCurrentMode()
    }

    private fun onVerificationSuccess() {
        userManager.update {
            this.passcodeAttempts = 0
            this.passcodeAutoMisses = 0
            this.patternAttempts = 0
            this.lastUseTime = System.currentTimeMillis()
        }

        if (isVerificationMode) {
            
            setResult(RESULT_OK, SecurityResultNonce.resultData(intent))
        } else {
            
            (application as? AppUnlockListener)?.onAppUnlocked()
            
            
            PendingScreenLockDeeplink.poll()?.let { pendingIntent ->
                
                
                
                
                val launched = runCatching { startActivity(pendingIntent) }
                    .onFailure { e -> L.w { "[ScreenLockActivity] replay startActivity failed: ${e.message}" } }
                    .isSuccess
                if (launched) {
                    ScreenLockUtil.markRecentlyUnlocked()
                    L.i { "[ScreenLockActivity] replaying queued deeplink after unlock" }
                }
            }
        }

        finish()
    }

    private fun prepareVerify(inputValue: String, isManualVerify: Boolean) {
        val storePasscode = userManager.getUserData()?.passcode
        if (storePasscode.isNullOrEmpty()) {
            L.i { "[ScreenLockActivity] current passcode is null" }
            return
        }

        val userData = userManager.getUserData()
        val attempts = userData?.passcodeAttempts ?: 0
        val autoMisses = userData?.passcodeAutoMisses ?: 0

        
        if (!isManualVerify && !PasscodeAutoVerifyPolicy.allowsAutoVerify(attempts, autoMisses)) {
            L.i { "[ScreenLockActivity] auto verify blocked attempts=$attempts autoMisses=$autoMisses" }
            return
        }

        verifyPasscode(storePasscode, inputValue, isManualVerify)
    }

    private fun verifyPattern(credential: PatternCredential, newSequence: List<Int>, legacySequence: List<Int>) {
        val result = PatternUnlockPolicy.evaluate(
            newSequence = newSequence,
            legacySequence = legacySequence,
            createdWithNewRule = credential.createdWithNewRule,
            currentAttempts = userManager.getUserData()?.patternAttempts ?: 0,
        ) { PasscodeUtil.verifyPassword(credential.hash, credential.salt, it) }

        when (result) {
            is PatternUnlockPolicy.Result.Unlocked -> {
                if (result.viaLegacyRule) {
                    L.i { "[ScreenLockActivity] pattern unlocked rule=legacy" }
                } else if (!credential.createdWithNewRule) {
                    
                    
                    userManager.update { this.pattern = PatternCredential.create(newSequence).format() }
                    L.i { "[ScreenLockActivity] pattern credential marked" }
                }
                onVerificationSuccess()
                AppLockCallbackManager.notifyUnlockSuccess()
            }

            is PatternUnlockPolicy.Result.Failed -> onPatternMismatch(result)
        }
    }

    private fun onPatternMismatch(result: PatternUnlockPolicy.Result.Failed) {
        mBinding.patternLockView.setViewMode(PatternLockView.PatternViewMode.WRONG)
        mBinding.patternLockView.clearPatternDelayed(1000)

        userManager.update {
            this.patternAttempts = result.attempts
        }

        when (val ladder = result.ladder) {
            
            is PatternUnlockPolicy.Ladder.Logout -> {
                logoutManager.doLogoutWithoutRemoveData()
                return
            }

            
            is PatternUnlockPolicy.Ladder.Warning -> {
                ToastUtil.show(R.string.settings_pattern_error_tips)
                mBinding.tvErrorTips.visibility = View.VISIBLE
                mBinding.tvErrorTips.text = getString(R.string.settings_too_many_attempts_warning)
            }

            
            is PatternUnlockPolicy.Ladder.Delay -> {
                ToastUtil.show(R.string.settings_pattern_error_tips)
                startCountdownTimer(ladder.seconds, true)
            }

            is PatternUnlockPolicy.Ladder.None -> ToastUtil.show(R.string.settings_pattern_error_tips)
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
                mBinding.etPasscode1.text?.clear()

                val attemptsCount = (userManager.getUserData()?.passcodeAttempts ?: 0)
                userManager.update {
                    this.passcodeAttempts = attemptsCount + 1
                }

                
                if (attemptsCount + 1 >= 10) {
                    logoutManager.doLogoutWithoutRemoveData()
                    return
                }

                
                ToastUtil.show(R.string.settings_passcode_error_tips)

                when {
                    attemptsCount + 1 == 9 -> {
                        
                        mBinding.tvErrorTips.visibility = View.VISIBLE
                        mBinding.tvErrorTips.text = getString(R.string.settings_too_many_attempts_warning)
                    }

                    attemptsCount + 1 >= 5 -> {
                        
                        val delaySeconds = (attemptsCount + 1 - 4) * (attemptsCount + 1 - 4)
                        startCountdownTimer(delaySeconds, false)
                    }
                }
            } else {
                val autoMisses = (userManager.getUserData()?.passcodeAutoMisses ?: 0) + 1
                userManager.update {
                    this.passcodeAutoMisses = autoMisses
                }
                if (autoMisses == PasscodeAutoVerifyPolicy.AUTO_MISS_LIMIT) {
                    L.i { "[ScreenLockActivity] auto verify budget spent autoMisses=$autoMisses" }
                }
            }
        }
    }

    private fun startCountdownTimer(delaySeconds: Int, isPattern: Boolean) {
        if (isPattern) {
            patternCountdownJob?.cancel()
            mBinding.patternLockView.setInputEnabled(false)
        } else {
            passcodeCountdownJob?.cancel()
            mBinding.etPasscode1.isEnabled = false
            mBinding.btnSubmit.isEnabled = false
        }

        val job = lifecycleScope.launch {
            var remainingSeconds = delaySeconds
            while (remainingSeconds > 0) {
                
                if (isPattern == isPatternMode) {
                    mBinding.tvErrorTips.visibility = View.VISIBLE
                    updateCountdownText(remainingSeconds)
                }

                delay(1000L)
                remainingSeconds--
            }

            if (isPattern) {
                mBinding.patternLockView.setInputEnabled(true)
            } else {
                mBinding.etPasscode1.isEnabled = true
                mBinding.etPasscode1.text?.clear()
                
            }

            
            if (isPattern == isPatternMode) {
                mBinding.tvErrorTips.visibility = View.INVISIBLE
            }
        }

        if (isPattern) {
            patternCountdownJob = job
        } else {
            passcodeCountdownJob = job
        }
    }

    private fun updateCountdownText(remainingSeconds: Int) {
        if (remainingSeconds > 0) {
            mBinding.tvErrorTips.text = getString(R.string.settings_service_unavailable_countdown, remainingSeconds)
        } else {
            mBinding.tvErrorTips.visibility = View.INVISIBLE
        }
    }

    private fun checkAndRestoreCountdown() {
        val userData = userManager.getUserData() ?: return

        
        val patternAttempts = userData.patternAttempts
        if (patternAttempts in 5..8) {
            val delaySeconds = (patternAttempts - 4) * (patternAttempts - 4)
            startCountdownTimer(delaySeconds, true)
        }

        val passcodeAttempts = userData.passcodeAttempts
        if (passcodeAttempts in 5..8) {
            val delaySeconds = (passcodeAttempts - 4) * (passcodeAttempts - 4)
            startCountdownTimer(delaySeconds, false)
        }

        updateDelayStatusForCurrentMode()
    }

    private fun updateDelayStatusForCurrentMode() {
        
        shortEntryHintJob?.cancel()
        shortEntryHintJob = null
        defaultErrorTipsColors?.let { mBinding.tvErrorTips.setTextColor(it) }

        val userData = userManager.getUserData() ?: return

        val attemptsCount = if (isPatternMode) {
            userData.patternAttempts
        } else {
            userData.passcodeAttempts
        }

        if (attemptsCount == 9) {
            mBinding.tvErrorTips.visibility = View.VISIBLE
            mBinding.tvErrorTips.text = getString(R.string.settings_too_many_attempts_warning)
            return
        }

        
        mBinding.tvErrorTips.visibility = View.INVISIBLE
    }
}