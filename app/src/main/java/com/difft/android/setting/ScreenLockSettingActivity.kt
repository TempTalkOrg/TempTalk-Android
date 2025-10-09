package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.user.UserManager
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.databinding.ActivityScreenLockSettingBinding
import com.difft.android.login.BindAccountActivity
import com.difft.android.login.ScreenLockActivity
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import util.TimeUtils
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@AndroidEntryPoint
class ScreenLockSettingActivity : BaseActivity() {

    companion object {
        private const val LOCK_TYPE_PATTERN = "pattern"
        private const val LOCK_TYPE_PASSCODE = "passcode"
        
        // 验证操作类型常量
        private const val ACTION_ENABLE = "enable"
        private const val ACTION_DELETE = "delete"

        fun startActivity(activity: Activity) {
            val intent = Intent(activity, ScreenLockSettingActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityScreenLockSettingBinding by viewbind()

    @Inject
    lateinit var userManager: UserManager

    // 记录当前正在设置的锁定类型
    private var pendingLockType: String? = null
    
    // 记录当前待执行的操作类型 ("enable" 或 "delete")
    private var pendingAction: String? = null

    // 处理绑定账号的结果
    private val bindAccountLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            // 绑定成功，继续进入对应的设置页面
            when (pendingLockType) {
                LOCK_TYPE_PATTERN -> SetPatternActivity.startActivity(this)
                LOCK_TYPE_PASSCODE -> SetPasscodeActivity.startActivity(this)
            }
        }
        // 绑定失败或取消时不需要额外操作，因为开关状态已经在点击时重置过了
        pendingLockType = null
    }

    // 处理屏幕锁定验证的结果
    private val screenLockVerificationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            // 验证成功，根据pendingAction执行对应操作
            when (pendingAction) {
                ACTION_DELETE -> {
                    // 删除对应的锁定功能
                    when (pendingLockType) {
                        LOCK_TYPE_PATTERN -> deletePattern()
                        LOCK_TYPE_PASSCODE -> deletePasscode()
                    }
                    updateScreenLockView() // 更新UI状态
                }
                ACTION_ENABLE -> {
                    // 继续执行开启锁定的流程
                    proceedWithEnableLock(pendingLockType!!)
                }
            }
        }
        // 无论验证成功还是失败，都清理pending状态
        pendingLockType = null
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
    }

    override fun onResume() {
        super.onResume()
        updateScreenLockView()
        checkAndShowBindingPrompt()
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        // Pattern unlock switch - 使用点击监听器，阻止自动状态切换
        mBinding.switchPattern.setOnClickListener { view ->
            val userData = userManager.getUserData()
            val currentlyEnabled = !userData?.pattern.isNullOrEmpty()
            val targetState = !currentlyEnabled

            // 阻止开关自动切换状态
            mBinding.switchPattern.isChecked = currentlyEnabled

            handleLockToggle(LOCK_TYPE_PATTERN, targetState)
        }

        // Passcode unlock switch - 使用点击监听器，阻止自动状态切换
        mBinding.switchPasscode.setOnClickListener { view ->
            val userData = userManager.getUserData()
            val currentlyEnabled = !userData?.passcode.isNullOrEmpty()
            val targetState = !currentlyEnabled

            // 阻止开关自动切换状态
            mBinding.switchPasscode.isChecked = currentlyEnabled

            handleLockToggle(LOCK_TYPE_PASSCODE, targetState)
        }

        // Pattern show path switch
        mBinding.switchPatternShowPath.setOnCheckedChangeListener { _, isChecked ->
            userManager.update {
                this.patternShowPath = isChecked
            }
        }

        // Timeout setting
        mBinding.clTimeout.setOnClickListener {
            SetPasscodeTimeoutActivity.startActivity(this)
        }
    }

    private fun updateScreenLockView() {
        userManager.getUserData()?.let { userData ->
            // Update pattern switch - 直接设置状态，不需要移除监听器
            mBinding.switchPattern.isChecked = !userData.pattern.isNullOrEmpty()

            // Update passcode switch - 直接设置状态，不需要移除监听器
            mBinding.switchPasscode.isChecked = !userData.passcode.isNullOrEmpty()

            // Update pattern show path switch
            mBinding.switchPatternShowPath.setOnCheckedChangeListener(null)
            mBinding.switchPatternShowPath.isChecked = userData.patternShowPath
            mBinding.switchPatternShowPath.setOnCheckedChangeListener { _, isChecked ->
                userManager.update {
                    this.patternShowPath = isChecked
                }
            }

            // Show/hide pattern show path option based on pattern lock status
            val patternEnabled = !userData.pattern.isNullOrEmpty()
            mBinding.clPatternShowPath.visibility = if (patternEnabled) View.VISIBLE else View.GONE

            // Show/hide timeout setting based on any lock being enabled
            val anyLockEnabled = !userData.passcode.isNullOrEmpty() || !userData.pattern.isNullOrEmpty()
            if (anyLockEnabled) {
                mBinding.clTimeout.visibility = View.VISIBLE
                mBinding.tvTimeout.text = if (userData.passcodeTimeout == 0)
                    getString(R.string.settings_screen_lock_timeout_instant)
                else
                    TimeUtils.millis2FitTimeSpan(userData.passcodeTimeout.seconds.inWholeMilliseconds, 3, false)
            } else {
                mBinding.clTimeout.visibility = View.GONE
            }
        }
    }

    private fun deletePattern() {
        userManager.update {
            this.pattern = null
            this.patternShowPath = true
            this.patternAttempts = 0
        }
        updateScreenLockView()
        ToastUtil.show(getString(R.string.settings_pattern_disabled_title))
    }

    private fun deletePasscode() {
        userManager.update {
            this.passcode = null
            this.passcodeAttempts = 0
        }
        updateScreenLockView()
        ToastUtil.show(getString(R.string.settings_passcode_disabled_title))
    }

    /**
     * 检查是否需要绑定账号
     * @return true 如果需要绑定账号，false 如果已绑定
     */
    private fun needsAccountBinding(): Boolean {
        val userData = userManager.getUserData()
        return userData?.email.isNullOrEmpty() && userData?.phoneNumber.isNullOrEmpty()
    }

    /**
     * 处理屏幕锁定开关点击
     * @param lockType 锁定类型 (LOCK_TYPE_PATTERN 或 LOCK_TYPE_PASSCODE)
     * @param targetState 目标状态 (true=开启, false=关闭)
     */
    private fun handleLockToggle(lockType: String, targetState: Boolean) {
        if (targetState) {
            // 要开启锁定，先检查是否已有其他锁定方式
            val userData = userManager.getUserData()
            val hasAnyScreenLock = userData?.let { 
                !it.passcode.isNullOrEmpty() || !it.pattern.isNullOrEmpty() 
            } ?: false
            
            if (hasAnyScreenLock) {
                // 已设置其他锁定方式，需要先验证身份
                pendingLockType = lockType
                pendingAction = ACTION_ENABLE
                val intent = Intent(this, ScreenLockActivity::class.java).apply {
                    putExtra(ScreenLockActivity.EXTRA_VERIFICATION_MODE, true) // 标记为验证模式
                }
                screenLockVerificationLauncher.launch(intent)
            } else {
                // 没有设置任何锁定，直接执行开启流程
                proceedWithEnableLock(lockType)
            }
        } else {
            // 要关闭锁定，需要先验证身份
            pendingLockType = lockType
            pendingAction = ACTION_DELETE
            val intent = Intent(this, ScreenLockActivity::class.java).apply {
                putExtra(ScreenLockActivity.EXTRA_VERIFICATION_MODE, true) // 标记为验证模式
            }
            screenLockVerificationLauncher.launch(intent)
        }
    }
    
    /**
     * 继续执行开启锁定的流程
     * @param lockType 锁定类型
     */
    private fun proceedWithEnableLock(lockType: String) {
        if (needsAccountBinding()) {
            // 需要先绑定账号
            pendingLockType = lockType
            showBindingChoiceBottomDialog()
        } else {
            // 已绑定账号，直接进入设置页面
            when (lockType) {
                LOCK_TYPE_PATTERN -> SetPatternActivity.startActivity(this)
                LOCK_TYPE_PASSCODE -> SetPasscodeActivity.startActivity(this)
            }
        }
    }

    /**
     * 检查是否需要显示绑定账号的提示
     * 当用户已设置passcode但没有绑定账号时显示提示
     */
    private fun checkAndShowBindingPrompt() {
        val userData = userManager.getUserData() ?: return

        // 检查是否已设置passcode但没有绑定账号
        val hasPasscode = !userData.passcode.isNullOrEmpty()
        val hasPattern = !userData.pattern.isNullOrEmpty()
        val hasAnyLock = hasPasscode || hasPattern
        val needsBinding = needsAccountBinding()

        if (hasAnyLock && needsBinding) {
            showBindingPromptDialog()
        }
    }

    /**
     * 显示绑定账号提示弹窗
     */
    private fun showBindingPromptDialog() {
        ComposeDialogManager.showMessageDialog(
            context = this,
            title = getString(R.string.settings_bind_account_prompt_title),
            message = getString(R.string.settings_bind_account_prompt_message),
            confirmText = getString(R.string.settings_bind_account_prompt_bind),
            cancelText = getString(R.string.settings_bind_account_prompt_not_now),
            confirmButtonColor = androidx.compose.ui.graphics.Color(ContextCompat.getColor(this, com.difft.android.base.R.color.t_primary)),
            onConfirm = {
                // 点击Bind，显示选择绑定方式的底部弹窗
                showBindingChoiceBottomDialog()
            }
        )
    }

    var dialog: ComposeDialog? = null

    /**
     * 显示绑定方式选择的底部弹窗
     */
    private fun showBindingChoiceBottomDialog() {
        dialog = ComposeDialogManager.showBottomDialog(
            activity = this,
            layoutId = R.layout.dialog_bind_account_choice,
            onDismiss = { /* Dialog dismissed */ },
            onViewCreated = { view ->
                // 绑定邮箱按钮点击事件
                view.findViewById<TextView>(R.id.btn_bind_email).setOnClickListener {
                    dialog?.dismiss()
                    // 进入邮箱绑定流程
                    val intent = Intent(this, BindAccountActivity::class.java).apply {
                        putExtra(BindAccountActivity.INTENT_EXTRA_TYPE, BindAccountActivity.TYPE_BIND_EMAIL)
                    }
                    bindAccountLauncher.launch(intent)
                }

                // 绑定手机号按钮点击事件
                view.findViewById<TextView>(R.id.btn_bind_phone).setOnClickListener {
                    dialog?.dismiss()
                    // 进入手机号绑定流程
                    val intent = Intent(this, BindAccountActivity::class.java).apply {
                        putExtra(BindAccountActivity.INTENT_EXTRA_TYPE, BindAccountActivity.TYPE_BIND_PHONE)
                    }
                    bindAccountLauncher.launch(intent)
                }
            }
        )
    }
}
