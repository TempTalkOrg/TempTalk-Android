package com.difft.android.setting

import android.os.Bundle
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.applyDetailPageInsets
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.DualPaneUtils.showsDetailBackButton
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.user.NotificationContentDisplayType
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.chat.R
import com.difft.android.chat.focus.FocusActionLauncher
import com.difft.android.chat.focus.FocusDialogs
import com.difft.android.chat.focus.settings.FocusGroupPickerActivity
import com.difft.android.chat.focus.settings.FocusOthersNotifyActivity
import com.difft.android.chat.focus.settings.NotifyFocusSettingsContract
import com.difft.android.chat.focus.settings.NotifyFocusSettingsViewModel
import com.difft.android.chat.focus.showFocusError
import com.difft.android.chat.group.GroupGlobalNotificationSettingsActivity
import com.difft.android.databinding.ActivityNotificationSettingsBinding
import com.difft.android.setting.notificationtest.NotificationTestActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Notification settings as one grouped page (Compose body in an XML shell), with the Calls and
 * Critical Alerts pages under it. Can be displayed in both Activity (single-pane) and dual-pane mode.
 */
@AndroidEntryPoint
class NotificationSettingsFragment : Fragment(), SettingsIdiomPage {

    companion object {
        fun newInstance() = NotificationSettingsFragment()
    }

    private var _binding: ActivityNotificationSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var stateReader: NotificationSettingsStateReader

    @Inject
    lateinit var focusActionLauncher: FocusActionLauncher

    private val focusViewModel: NotifyFocusSettingsViewModel by viewModels()

    private val focusGroupPicker = registerForActivityResult(FocusGroupPickerActivity.Contract()) { gid ->
        if (gid != null) focusActionLauncher.applyPickedGroup(requireActivity(), gid)
    }

    private var refreshJob: Job? = null
    private var uiState by mutableStateOf<NotificationSettingsUiState?>(null)
    private var labels by mutableStateOf(NotificationSettingsLabels("", ""))

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            stateReader.readOrNull(page = "main")?.let { uiState = it }
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
        applyDetailPageInsets(view)

        setupBackButton(binding.ibBack)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                focusViewModel.effect.collect(::handleFocusEffect)
            }
        }
        binding.cvContent.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = ownsWindow()) {
                    val focusState by focusViewModel.state.collectAsStateWithLifecycle()
                    NotificationSettingsContent(
                        state = uiState,
                        labels = labels,
                        focus = focusState,
                        onFocusIntent = focusViewModel::dispatch,
                        actions = NotificationSettingsActions(
                            onFix = { openNotificationFix(it, stateReader) },
                            onCalls = { detailNavigation().push(this@NotificationSettingsFragment, CallNotificationSettingsDestination) },
                            onCriticalAlerts = { detailNavigation().push(this@NotificationSettingsFragment, CriticalAlertSettingsDestination) },
                            onGroupNotification = { detailNavigation().push(this@NotificationSettingsFragment, com.difft.android.chat.group.GroupGlobalNotificationSettingsDestination) },
                            onDisplayContent = { detailNavigation().push(this@NotificationSettingsFragment, NotificationContentDisplayDestination) },
                            onBackgroundConnection = { detailNavigation().push(this@NotificationSettingsFragment, BackgroundConnectionSettingsDestination) },
                            onGuide = {
                                requireActivity().openExternalBrowser(
                                    BackgroundConnectionHelpLink.build(BackgroundConnectionHelpLink.Source.NOTIFICATION_SETTINGS)
                                )
                            },
                            onNotificationTest = { detailNavigation().push(this@NotificationSettingsFragment, com.difft.android.setting.notificationtest.NotificationTestDestination) },
                        ),
                    )
                }
            }
        }
    }

    private fun handleFocusEffect(effect: NotifyFocusSettingsContract.Effect) {
        val activity = requireActivity()
        when (effect) {
            NotifyFocusSettingsContract.Effect.OpenGroupPicker -> focusGroupPicker.launch(Unit)
            NotifyFocusSettingsContract.Effect.EndFocus -> focusActionLauncher.endFocus(activity)
            NotifyFocusSettingsContract.Effect.ChangeEndTime -> focusActionLauncher.changeEndTime(activity)
            NotifyFocusSettingsContract.Effect.OpenOthersStillNotify -> FocusOthersNotifyActivity.start(activity)
            NotifyFocusSettingsContract.Effect.ShowPausedDialog ->
                FocusDialogs.groupNotifyPausedByFocus(activity) { focusActionLauncher.endFocus(activity) }

            NotifyFocusSettingsContract.Effect.ShowError -> showFocusError()
        }
    }

    override fun onResume() {
        super.onResume()
        focusViewModel.dispatch(NotifyFocusSettingsContract.Intent.Refresh)
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
