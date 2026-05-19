package com.difft.android.setting

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
import com.difft.android.chat.messages.MessageForegroundService
import com.difft.android.chat.util.MessageNotificationUtil
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var permissionRefreshJob: Job? = null
    private var criticalAlertClickJob: Job? = null

    private data class NotificationPermissionSnapshot(
        val canShowNotifications: Boolean,
        val fullScreenPermission: Boolean?,
        val notificationPolicyAccessGranted: Boolean,
    )

    private suspend fun queryNotificationPermissionSnapshot(
        includeFullScreen: Boolean,
    ): NotificationPermissionSnapshot = withContext(Dispatchers.IO) {
        val canNotify = messageNotificationUtil.canShowNotifications()
        val full = if (includeFullScreen) {
            messageNotificationUtil.hasFullScreenNotificationPermission()
        } else {
            null
        }
        val dnd = messageNotificationUtil.isNotificationPolicyAccessGranted()
        NotificationPermissionSnapshot(canNotify, full, dnd)
    }

    private fun applyCriticalAlertPolicyState(
        binding: ActivityNotificationSettingsBinding,
        policyAccessGranted: Boolean,
    ) {
        binding.tvCriticalAlertDisplay.text =
            if (policyAccessGranted) getString(R.string.notification_enable)
            else getString(R.string.notification_disable)
        binding.tvCriticalAlertSettings.visibility =
            if (policyAccessGranted) View.GONE else View.VISIBLE
    }

    private fun refreshNotificationPermissionUi() {
        permissionRefreshJob?.cancel()
        val isOppoEco = FullScreenPermissionHelper.isOppoEcosystemDevice()
        permissionRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val snap = queryNotificationPermissionSnapshot(includeFullScreen = !isOppoEco)

            val b = _binding ?: return@launch

            b.tvNotificationSettingsStatus.text =
                if (snap.canShowNotifications) getString(R.string.notification_enable)
                else getString(R.string.notification_disable)

            if (isOppoEco) {
                b.tvFullScreenNotificationSettingsStatus.visibility = View.GONE
                b.tvFullScreenNotificationTip.visibility = View.VISIBLE
                b.tvFullScreenNotificationTip.text =
                    FullScreenPermissionHelper.getFullScreenSettingTip()
            } else {
                val enabled = snap.fullScreenPermission ?: return@launch
                b.tvFullScreenNotificationSettingsStatus.visibility = View.VISIBLE
                if (enabled) {
                    b.tvFullScreenNotificationSettingsStatus.text =
                        getString(R.string.notification_enable)
                    b.tvFullScreenNotificationTip.visibility = View.GONE
                } else {
                    b.tvFullScreenNotificationSettingsStatus.text =
                        getString(R.string.notification_disable)
                    b.tvFullScreenNotificationTip.visibility = View.VISIBLE
                    b.tvFullScreenNotificationTip.text =
                        FullScreenPermissionHelper.getFullScreenSettingTip()
                }
            }

            applyCriticalAlertPolicyState(b, snap.notificationPolicyAccessGranted)
        }
    }

    private val dndPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        lifecycleScope.launch {
            val isGranted = withContext(Dispatchers.IO) {
                messageNotificationUtil.isNotificationPolicyAccessGranted()
            }
            val b = _binding ?: return@launch
            applyCriticalAlertPolicyState(b, isGranted)
        }
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

        binding.clNotificationSettings.setOnClickListener {
            messageNotificationUtil.openNotificationSettings(requireActivity())
        }

        binding.clFullScreenNotification.setOnClickListener {
            messageNotificationUtil.openFullScreenNotificationSettings(requireActivity())
        }

        binding.tvCriticalAlertSettings.text = ResUtils.getString(R.string.critical_alerts_content, PackageUtil.getAppName())
        binding.tvCriticalAlertSettings.visibility = View.VISIBLE

        binding.clCriticalAlertDisplay.setOnClickListener { handleCriticalAlertDisplayClick() }

        binding.clBackgroundConnection.setOnClickListener {
            BackgroundConnectionSettingsActivity.startActivity(requireActivity())
        }

        refreshNotificationPermissionUi()
    }

    private fun handleCriticalAlertDisplayClick() {
        criticalAlertClickJob?.cancel()
        criticalAlertClickJob = viewLifecycleOwner.lifecycleScope.launch {
            if (_binding == null || !isAdded) return@launch

            val hasFcm = pushUtil.fcmInitResult.value is FcmInitResult.Success
            val hasBgConnection = MessageForegroundService.isRunning

            val snap = queryNotificationPermissionSnapshot(includeFullScreen = true)
            if (_binding == null || !isAdded) return@launch

            if (snap.notificationPolicyAccessGranted) {
                openDndSettings()
                return@launch
            }

            val hasNotification = snap.canShowNotifications
            val fullScreenOk = snap.fullScreenPermission == true

            val canOpenDnd = if (hasFcm) {
                hasNotification && fullScreenOk
            } else {
                hasNotification && hasBgConnection && fullScreenOk
            }

            if (canOpenDnd) {
                openDndSettings()
                return@launch
            }

            val errorMessageRes = when {
                !hasNotification && (!hasFcm && !hasBgConnection) && !fullScreenOk ->
                    R.string.critical_alert_all_permission_check_failed
                !hasNotification -> R.string.critical_alert_notification_permission_check_failed
                !fullScreenOk -> R.string.critical_alert_fullscreen_permission_check_failed
                else -> R.string.critical_alert_background_connection_permission_check_failed
            }

            val ctx = context ?: return@launch
            ComposeDialogManager.showMessageDialog(
                context = ctx,
                title = getString(R.string.tip),
                message = getString(errorMessageRes),
                confirmText = getString(R.string.invite_ok),
                showCancel = false,
                cancelable = false,
            )
        }
    }

    private fun openDndSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        dndPermissionLauncher.launch(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        permissionRefreshJob?.cancel()
        permissionRefreshJob = null
        criticalAlertClickJob?.cancel()
        criticalAlertClickJob = null
        _binding = null
    }
}
