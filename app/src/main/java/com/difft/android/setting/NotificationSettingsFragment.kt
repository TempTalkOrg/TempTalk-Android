package com.difft.android.setting

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationFixJump
import com.difft.android.base.notification.NotificationReadinessProvider
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.user.NotificationContentDisplayType
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.group.GroupGlobalNotificationSettingsActivity
import com.difft.android.chat.messages.MessageForegroundService
import com.difft.android.chat.util.CriticalAlertBypass
import com.difft.android.chat.util.MessageSoundLabel
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.util.NotificationChannelManager
import com.difft.android.databinding.ActivityNotificationSettingsBinding
import com.difft.android.push.FcmInitResult
import com.difft.android.push.PushUtil
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Notification settings as one grouped page (Compose body in an XML shell).
 * Can be displayed in both Activity (single-pane) and dual-pane mode.
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

    @Inject
    lateinit var readinessProvider: NotificationReadinessProvider

    @Inject
    lateinit var channelManager: NotificationChannelManager

    private var refreshJob: Job? = null
    private var uiState by mutableStateOf<NotificationSettingsUiState?>(null)
    private var labels by mutableStateOf(NotificationSettingsLabels("", ""))

    /** Reads every status on IO; the critical-alert bypass is reconciled first so its read-back is current. */
    private suspend fun queryState(): NotificationSettingsUiState = withContext(Dispatchers.IO) {
        val bypass = try {
            channelManager.reconcileCriticalAlertBypass()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[NotificationSettings] bypass reconcile failed error=${e.javaClass.simpleName}" }
            CriticalAlertBypass.NOT_APPLICABLE
        }
        NotificationSettingsUiState(
            readiness = readinessProvider.readBlocking(),
            sdkInt = Build.VERSION.SDK_INT,
            dndAccessGranted = messageNotificationUtil.isNotificationPolicyAccessGranted(),
            bypass = bypass,
            fcmUnavailable = pushUtil.fcmInitResult.value.let { it is FcmInitResult.PlayServicesUnavailable || it is FcmInitResult.Failure },
            backgroundConnectionRunning = MessageForegroundService.isRunning,
        )
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val state = queryState()
                val sound = withContext(Dispatchers.IO) { MessageSoundLabel.read(requireContext().applicationContext) }
                uiState = state
                labels = labels.copy(messageSound = sound)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                L.w { "[NotificationSettings] refresh failed error=${e.javaClass.simpleName}" }
            }
        }
    }

    private fun openFix(fix: NotificationFix) {
        if (!NotificationFixJump.open(requireActivity(), fix)) {
            L.w { "[NotificationSettings] fix jump failed fix=$fix" }
            ToastUtil.show(R.string.notification_settings_open_fail)
        }
    }

    private val dndPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (_binding != null) refresh()
    }

    /** Access pages are launched for a result so the row refreshes on return; an unmet item opens the category page. */
    private fun onCriticalAlertsClick() {
        when (val fix = uiState?.criticalFix ?: return) {
            NotificationFix.DND_ACCESS, NotificationFix.DND_ACCESS_LIST -> {
                val opened = NotificationFixJump.openForResult(requireActivity(), fix) { dndPermissionLauncher.launch(it) }
                if (!opened) {
                    L.w { "[NotificationSettings] fix jump failed fix=$fix" }
                    ToastUtil.show(R.string.notification_settings_open_fail)
                }
            }
            else -> openFix(fix)
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
        binding.cvContent.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme {
                    NotificationSettingsContent(
                        state = uiState,
                        labels = labels,
                        actions = NotificationSettingsActions(
                            onFix = ::openFix,
                            onCriticalAlerts = ::onCriticalAlertsClick,
                            onGroupNotification = { GroupGlobalNotificationSettingsActivity.start(requireActivity()) },
                            onMessageSound = { messageNotificationUtil.openMessageNotificationChannelSettings(requireActivity()) },
                            onDisplayContent = { NotificationContentDisplaySettingsActivity.start(requireActivity()) },
                            onBackgroundConnection = { BackgroundConnectionSettingsActivity.startActivity(requireActivity()) },
                            onGuide = {
                                requireActivity().openExternalBrowser(
                                    BackgroundConnectionHelpLink.build(BackgroundConnectionHelpLink.Source.NOTIFICATION_SETTINGS)
                                )
                            },
                        ),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        labels = labels.copy(
            groupNotification = when (userManager.getUserData()?.globalNotification) {
                GlobalNotificationType.ALL.value -> getString(R.string.notification_all)
                GlobalNotificationType.MENTION.value -> getString(R.string.notification_mention_only)
                GlobalNotificationType.OFF.value -> getString(R.string.notification_off)
                else -> getString(R.string.notification_all)
            },
            displayContent = when (userManager.getUserData()?.notificationContentDisplayType) {
                NotificationContentDisplayType.NAME_AND_CONTENT.value -> getString(R.string.notification_display_name_and_content)
                NotificationContentDisplayType.NAME_ONLY.value -> getString(R.string.notification_only_name)
                NotificationContentDisplayType.NO_NAME_OR_CONTENT.value -> getString(R.string.notification_no_name_or_content)
                else -> getString(R.string.notification_display_name_and_content)
            },
        )
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob?.cancel()
        refreshJob = null
        _binding = null
    }
}
