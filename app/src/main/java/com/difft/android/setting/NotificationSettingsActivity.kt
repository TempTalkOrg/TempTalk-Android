package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.difft.android.base.BaseActivity
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.user.NotificationContentDisplayType
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.R
import com.difft.android.chat.group.GroupGlobalNotificationSettingsActivity
import com.difft.android.databinding.ActivityNotificationSettingsBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.messages.MessageServiceManager
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

    private var isCriticalAlertCheckBackgroundConnection: Boolean = false

    var isCriticalAlertSettingsOpened: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCriticalAlertSettingsOpened = messageNotificationUtil.isNotificationPolicyAccessGranted()
        mBinding.ibBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        initView()
    }

    override fun onDestroy() {
        super.onDestroy()
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

        mBinding.tvCriticalAlertSettings.text = ResUtils.getString(com.difft.android.chat.R.string.critical_alerts_content, PackageUtil.getAppName())
        mBinding.tvCriticalAlertDisplay.text = if (::messageNotificationUtil.isInitialized && messageNotificationUtil.isNotificationPolicyAccessGranted())
            getString(com.difft.android.chat.R.string.notification_enable) else getString(com.difft.android.chat.R.string.notification_disable)
        mBinding.clCriticalAlertDisplay.setOnClickListener {
            if (!isCriticalAlertCheckBackgroundConnection && !messageServiceManager.checkBackgroundConnectionRequirements()) {
                ComposeDialogManager.showMessageDialog(
                    context = this@NotificationSettingsActivity,
                    title = getString(R.string.tip),
                    message = getString(R.string.critical_alert_background_connection_check_failed),
                    confirmText = getString(R.string.invite_ok),
                    showCancel = false,
                    cancelable = false,
                    onConfirm = {
                        isCriticalAlertCheckBackgroundConnection = true
                    },
                    onDismiss = {
                        isCriticalAlertCheckBackgroundConnection = true
                    }
                )
            } else {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                dndPermissionLauncher.launch(intent)
            }
        }

        mBinding.clBackgroundConnection.setOnClickListener {
            BackgroundConnectionSettingsActivity.startActivity(this@NotificationSettingsActivity)
        }
    }


    private val dndPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 当用户从设置页返回时，会回调到这里
        isCriticalAlertSettingsOpened = messageNotificationUtil.isNotificationPolicyAccessGranted()
    }

}