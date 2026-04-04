package com.difft.android.setting

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.user.NotificationContentDisplayType
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.call.util.FullScreenPermissionHelper
import com.difft.android.chat.R
import com.difft.android.chat.group.GroupGlobalNotificationSettingsActivity
import com.difft.android.databinding.ActivityNotificationSettingsBinding
import com.difft.android.push.FcmInitResult
import com.difft.android.push.PushUtil
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.messages.MessageForegroundService
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import javax.inject.Inject

/**
 * Fragment for notification settings
 * Can be displayed in both Activity (single-pane) and dual-pane mode
 */
@AndroidEntryPoint
class NotificationSettingsFragment : Fragment() {

    companion object {
        fun newInstance() = NotificationSettingsFragment()
    }

    private var _binding: ActivityNotificationSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var pushUtil: PushUtil

    private val dndPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 当用户从设置页返回时，会回调到这里
        val isGranted = messageNotificationUtil.isNotificationPolicyAccessGranted()
        binding.tvCriticalAlertDisplay.text = if (isGranted) getString(R.string.notification_enable) else getString(R.string.notification_disable)
        binding.tvCriticalAlertSettings.visibility = if (isGranted) View.GONE else View.VISIBLE
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityNotificationSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBackButton(binding.ibBack)
    }

    override fun onResume() {
        super.onResume()
        initView()
    }

    private fun initView() {
        // 设置全局通知开关状态
        binding.tvGlobalNotification.text = when (userManager.getUserData()?.globalNotification) {
            GlobalNotificationType.ALL.value -> getString(R.string.notification_all)
            GlobalNotificationType.MENTION.value -> getString(R.string.notification_mention_only)
            GlobalNotificationType.OFF.value -> getString(R.string.notification_off)
            else -> getString(R.string.notification_all)
        }
        binding.clGlobalNotification.setOnClickListener {
            GroupGlobalNotificationSettingsActivity.start(requireActivity())
        }

        binding.clMessageSound.setOnClickListener {
            messageNotificationUtil.openMessageNotificationChannelSettings(requireActivity())
        }

        // 设置通知显示内容状态
        binding.tvNotificationDisplay.text = when (userManager.getUserData()?.notificationContentDisplayType) {
            NotificationContentDisplayType.NAME_AND_CONTENT.value -> getString(R.string.notification_display_name_and_content)
            NotificationContentDisplayType.NAME_ONLY.value -> getString(R.string.notification_only_name)
            NotificationContentDisplayType.NO_NAME_OR_CONTENT.value -> getString(R.string.notification_no_name_or_content)
            else -> getString(R.string.notification_display_name_and_content)
        }
        binding.clNotificationDisplay.setOnClickListener {
            NotificationContentDisplaySettingsActivity.start(requireActivity())
        }

        binding.tvNotificationSettingsStatus.text = if (messageNotificationUtil.canShowNotifications())
            getString(R.string.notification_enable) else getString(R.string.notification_disable)

        binding.clNotificationSettings.setOnClickListener {
            messageNotificationUtil.openNotificationSettings(requireActivity())
        }

        if (FullScreenPermissionHelper.isOppoEcosystemDevice()) {
            binding.tvFullScreenNotificationSettingsStatus.visibility = View.GONE
            binding.tvFullScreenNotificationTip.visibility = View.VISIBLE
            binding.tvFullScreenNotificationTip.text = FullScreenPermissionHelper.getFullScreenSettingTip()
        } else {
            binding.tvFullScreenNotificationSettingsStatus.visibility = View.VISIBLE
            if (messageNotificationUtil.hasFullScreenNotificationPermission()) {
                binding.tvFullScreenNotificationSettingsStatus.text = getString(R.string.notification_enable)
                binding.tvFullScreenNotificationTip.visibility = View.GONE
            } else {
                binding.tvFullScreenNotificationSettingsStatus.text = getString(R.string.notification_disable)
                binding.tvFullScreenNotificationTip.visibility = View.VISIBLE
                binding.tvFullScreenNotificationTip.text = FullScreenPermissionHelper.getFullScreenSettingTip()
            }
        }

        binding.clFullScreenNotification.setOnClickListener {
            messageNotificationUtil.openFullScreenNotificationSettings(requireActivity())
        }

        binding.tvCriticalAlertSettings.text = ResUtils.getString(R.string.critical_alerts_content, PackageUtil.getAppName())
        binding.tvCriticalAlertSettings.visibility = View.VISIBLE

        if (::messageNotificationUtil.isInitialized && messageNotificationUtil.isNotificationPolicyAccessGranted()) {
            binding.tvCriticalAlertDisplay.text = getString(R.string.notification_enable)
        } else {
            binding.tvCriticalAlertDisplay.text = getString(R.string.notification_disable)
        }

        binding.clCriticalAlertDisplay.setOnClickListener {
            val hasFcm = pushUtil.fcmInitResult.value is FcmInitResult.Success
            val hasNotification = messageNotificationUtil.canShowNotifications()
            val hasBgConnection = MessageForegroundService.isRunning
            val dndSettingsEnabled = messageNotificationUtil.isNotificationPolicyAccessGranted()
            val fullScreenNotificationEnabled = messageNotificationUtil.hasFullScreenNotificationPermission()

            if (dndSettingsEnabled) {
                openDndSettings()
                return@setOnClickListener
            }

            val canOpenDnd = if (hasFcm) hasNotification && fullScreenNotificationEnabled else (hasNotification && hasBgConnection && fullScreenNotificationEnabled)

            if (canOpenDnd) {
                openDndSettings()
                return@setOnClickListener
            }

            val errorMessageRes = when {
                !hasNotification && (!hasFcm && !hasBgConnection) && !fullScreenNotificationEnabled -> R.string.critical_alert_all_permission_check_failed
                !hasNotification -> R.string.critical_alert_notification_permission_check_failed
                !fullScreenNotificationEnabled -> R.string.critical_alert_fullscreen_permission_check_failed
                else -> R.string.critical_alert_background_connection_permission_check_failed
            }

            ComposeDialogManager.showMessageDialog(
                context = requireContext(),
                title = getString(R.string.tip),
                message = getString(errorMessageRes),
                confirmText = getString(R.string.invite_ok),
                showCancel = false,
                cancelable = false,
            )
        }

        binding.clBackgroundConnection.setOnClickListener {
            BackgroundConnectionSettingsActivity.startActivity(requireActivity())
        }
    }

    private fun openDndSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        dndPermissionLauncher.launch(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

