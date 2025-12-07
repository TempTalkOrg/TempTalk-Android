package com.difft.android.setting

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.user.NotificationContentDisplayType
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.group.GroupGlobalNotificationSettingsActivity
import com.difft.android.databinding.ActivityNotificationSettingsBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.messages.MessageForegroundService
import org.thoughtcrime.securesms.messages.MessageServiceManager
import org.thoughtcrime.securesms.util.AutoStartPermissionHelper
import org.thoughtcrime.securesms.util.DeviceProperties
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import javax.inject.Inject

@AndroidEntryPoint
class NotificationSettingsActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, NotificationSettingsActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityNotificationSettingsBinding by viewbind()

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var messageServiceManager: MessageServiceManager


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding.ibBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        initView()
    }

    private fun initView() {
        // 设置全局通知开关状态
        mBinding.tvGlobalNotification.text = when (userManager.getUserData()?.globalNotification) {
            GlobalNotificationType.ALL.value -> getString(com.difft.android.chat.R.string.notification_all)
            GlobalNotificationType.MENTION.value -> getString(com.difft.android.chat.R.string.notification_mention_only)
            GlobalNotificationType.OFF.value -> getString(com.difft.android.chat.R.string.notification_off)
            else -> getString(com.difft.android.chat.R.string.notification_all)
        }
        mBinding.clGlobalNotification.setOnClickListener {
            GroupGlobalNotificationSettingsActivity.start(this@NotificationSettingsActivity)
        }

        mBinding.clMessageSound.setOnClickListener {
            messageNotificationUtil.openMessageNotificationChannelSettings(this)
        }

        // 设置通知显示内容状态
        mBinding.tvNotificationDisplay.text = when (userManager.getUserData()?.notificationContentDisplayType) {
            NotificationContentDisplayType.NAME_AND_CONTENT.value -> getString(com.difft.android.chat.R.string.notification_display_name_and_content)
            NotificationContentDisplayType.NAME_ONLY.value -> getString(com.difft.android.chat.R.string.notification_only_name)
            NotificationContentDisplayType.NO_NAME_OR_CONTENT.value -> getString(com.difft.android.chat.R.string.notification_no_name_or_content)
            else -> getString(com.difft.android.chat.R.string.notification_display_name_and_content)
        }
        mBinding.clNotificationDisplay.setOnClickListener {
            NotificationContentDisplaySettingsActivity.start(this@NotificationSettingsActivity)
        }

        mBinding.tvNotificationSettingsStatus.text = if (messageNotificationUtil.canShowNotifications())
            getString(com.difft.android.chat.R.string.notification_enable) else getString(com.difft.android.chat.R.string.notification_disable)

        mBinding.clNotificationSettings.setOnClickListener {
            messageNotificationUtil.openNotificationSettings(this@NotificationSettingsActivity)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mBinding.tvFullScreenNotificationSettingsStatus.visibility = View.VISIBLE
            if (messageNotificationUtil.hasFullScreenNotificationPermission()) {
                mBinding.tvFullScreenNotificationSettingsStatus.text = getString(com.difft.android.chat.R.string.notification_enable)
            } else {
                mBinding.tvFullScreenNotificationSettingsStatus.text = getString(com.difft.android.chat.R.string.notification_disable)
            }
        } else {
            mBinding.tvFullScreenNotificationSettingsStatus.visibility = View.GONE
        }

        mBinding.clFullScreenNotification.setOnClickListener {
            messageNotificationUtil.openFullScreenNotificationSettings(this@NotificationSettingsActivity)
        }

        mBinding.switchBackgroundConnection.isChecked = MessageForegroundService.isRunning
        mBinding.switchBackgroundConnection.setOnClickListener {
            if (!MessageForegroundService.isRunning) {
                // 开启Service：先检查前置条件
                if (!messageServiceManager.checkBackgroundConnectionRequirements()) {
                    // 前置条件不满足，提示用户
                    L.w { "Background connection requirements not met" }
                    mBinding.switchBackgroundConnection.isChecked = false
                    ToastUtil.showLong(getString(com.difft.android.chat.R.string.background_connection_check_failed))
                    return@setOnClickListener
                }

                userManager.update { autoStartMessageService = true }
                L.i { "[MessageService] User enabled service, autoStartMessageService set to true" }

                messageServiceManager.startService()
                mBinding.switchBackgroundConnection.isChecked = true
            } else {
                // 关闭Service
                userManager.update { autoStartMessageService = false }
                L.i { "[MessageService] User disabled service, autoStartMessageService set to false" }

                messageServiceManager.stopService()
                mBinding.switchBackgroundConnection.isChecked = false
            }
        }

        mBinding.tvCriticalAlertSettings.text = ResUtils.getString(com.difft.android.chat.R.string.critical_alerts_content, PackageUtil.getAppName())
        mBinding.tvCriticalAlertDisplay.text = if (::messageNotificationUtil.isInitialized && messageNotificationUtil.isNotificationPolicyAccessGranted())
            getString(com.difft.android.chat.R.string.notification_enable) else getString(com.difft.android.chat.R.string.notification_disable)
        mBinding.clCriticalAlertDisplay.setOnClickListener {
            messageNotificationUtil.openNotificationDndSettings(this@NotificationSettingsActivity)
        }

        // 初始化后台连接检查项
        initBackgroundConnectionChecks()
    }

    /**
     * 初始化后台连接检查项
     *
     * 显示独立的检查项（优先级从高到低）：
     * 1. 后台限制 - 对应"允许后台使用"开关（Android 9+）
     * 2. 电池优化 - 对应"电池优化"设置（Android 6+）
     * 3. 后台数据 - 对应"后台数据"设置（Android 7+）
     * 4. 自启动管理 - 中国品牌特有
     *
     * 交互设计：
     * - 电池优化：正常状态隐藏箭头（直接弹系统窗口，无需引导）
     * - 其他检查项：始终显示箭头（需要引导用户去设置页面）
     * - 使用 INVISIBLE 而不是 GONE 来隐藏箭头，保持文字对齐
     */
    private fun initBackgroundConnectionChecks() {
        // 检查电池优化和后台限制状态
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBatteryOpt = powerManager.isIgnoringBatteryOptimizations(packageName)

        val isBackgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DeviceProperties.isBackgroundRestricted(this)
        } else {
            false
        }

        // 1. 后台限制检查（Android 9+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mBinding.clBackgroundRestriction.visibility = View.VISIBLE
            mBinding.dividerBackgroundRestriction.visibility = View.VISIBLE
            mBinding.tvBackgroundRestrictionStatus.text = if (isBackgroundRestricted) {
                getString(com.difft.android.chat.R.string.background_restriction_restricted)
            } else {
                getString(com.difft.android.chat.R.string.background_restriction_unrestricted)
            }
            mBinding.ivArrowBackgroundRestriction.visibility = View.VISIBLE
            mBinding.clBackgroundRestriction.setOnClickListener {
                openBackgroundRestrictionSettings()
            }
        } else {
            mBinding.clBackgroundRestriction.visibility = View.GONE
            mBinding.dividerBackgroundRestriction.visibility = View.GONE
        }

        // 2. 电池优化检查（Android 6+）
        mBinding.clBatteryOptimization.visibility = View.VISIBLE
        mBinding.dividerBatteryOptimization.visibility = View.VISIBLE
        mBinding.tvBatteryOptimizationStatus.text = if (isIgnoringBatteryOpt) {
            getString(com.difft.android.chat.R.string.battery_optimization_ignored)
        } else {
            getString(com.difft.android.chat.R.string.battery_optimization_not_ignored)
        }

        if (isIgnoringBatteryOpt) {
            mBinding.ivArrowBatteryOptimization.visibility = View.INVISIBLE
            mBinding.clBatteryOptimization.isClickable = false
            mBinding.clBatteryOptimization.setOnClickListener(null)
        } else {
            mBinding.ivArrowBatteryOptimization.visibility = View.VISIBLE
            mBinding.clBatteryOptimization.isClickable = true
            mBinding.clBatteryOptimization.setOnClickListener {
                openBatteryOptimizationSettings()
            }
        }

        // 3. 后台数据检查（Android 7+）
        val dataSaverState = DeviceProperties.getDataSaverState(this)
        mBinding.clDataSaver.visibility = View.VISIBLE
        mBinding.dividerDataSaver.visibility = View.VISIBLE
        mBinding.tvDataSaverStatus.text = if (dataSaverState.isRestricted) {
            getString(com.difft.android.chat.R.string.data_saver_restricted)
        } else {
            getString(com.difft.android.chat.R.string.data_saver_unrestricted)
        }
        mBinding.ivArrowDataSaver.visibility = View.VISIBLE
        mBinding.clDataSaver.setOnClickListener {
            openDataSaverSettings()
        }

        // 4. 自启动管理
        if (AutoStartPermissionHelper.canOpenAutoStartSettings(this)) {
            mBinding.clAutoStart.visibility = View.VISIBLE
            mBinding.dividerAutoStart.visibility = View.VISIBLE
            mBinding.tvAutoStartStatus.text = getString(com.difft.android.chat.R.string.auto_start_need_check)
            mBinding.clAutoStart.setOnClickListener {
                val success = AutoStartPermissionHelper.openAutoStartSettings(this)
                if (!success) {
                    L.w { "Failed to open auto-start settings" }
                }
            }
        } else {
            mBinding.clAutoStart.visibility = View.GONE
            mBinding.dividerAutoStart.visibility = View.GONE
        }
    }

    private fun openBackgroundRestrictionSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            L.e(e) { "Failed to open background restriction settings" }
        }
    }

    @SuppressLint("BatteryLife")
    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            L.e(e) { "Failed to open battery optimization settings" }
        }
    }

    private fun openDataSaverSettings() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            L.e(e) { "Failed to open data saver settings" }
        }
    }

}