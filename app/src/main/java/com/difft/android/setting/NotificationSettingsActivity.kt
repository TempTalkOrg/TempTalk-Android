package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.user.NotificationContentDisplayType
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.chat.group.GroupGlobalNotificationSettingsActivity
import com.difft.android.databinding.ActivityNotificationSettingsBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import util.concurrent.TTExecutors
import org.thoughtcrime.securesms.messages.MessageForegroundService
import org.thoughtcrime.securesms.util.ForegroundServiceUtil
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import org.thoughtcrime.securesms.util.UnableToStartException
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

        mBinding.tvNotificationSettingsStatus.text = if (messageNotificationUtil.hasNotificationPermission())
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
            userManager.update {
                this.autoStartMessageService = !MessageForegroundService.isRunning
            }
            if (MessageForegroundService.isRunning) {
                stopMessageForegroundService()
            } else {
                startMessageForegroundService()
            }
        }

        mBinding.tvCriticalAlertSettings.text = ResUtils.getString(com.difft.android.chat.R.string.critical_alerts_content, PackageUtil.getAppName())
        mBinding.tvCriticalAlertDisplay.text = if (::messageNotificationUtil.isInitialized && messageNotificationUtil.isNotificationPolicyAccessGranted())
            getString(com.difft.android.chat.R.string.notification_enable) else getString(com.difft.android.chat.R.string.notification_disable)
        mBinding.clCriticalAlertDisplay.setOnClickListener {
            messageNotificationUtil.openNotificationDndSettings(this@NotificationSettingsActivity)
        }

    }

    private fun startMessageForegroundService() {
        try {
            ForegroundServiceUtil.start(this, Intent(this, MessageForegroundService::class.java))
        } catch (e: UnableToStartException) {
            L.w { "Unable to start foreground service for websocket. Deferring to background to try with blocking" }
            TTExecutors.UNBOUNDED.execute {
                try {
                    ForegroundServiceUtil.startWhenCapable(this, Intent(this, MessageForegroundService::class.java))
                } catch (e: UnableToStartException) {
                    L.w { "Unable to start foreground service for websocket!" + e.stackTraceToString() }
                }
            }
        }
    }

    private fun stopMessageForegroundService() {
        ForegroundServiceUtil.stopService(MessageForegroundService::class.java)
    }
}