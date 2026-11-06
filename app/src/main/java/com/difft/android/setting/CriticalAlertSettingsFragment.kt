package com.difft.android.setting

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationFixJump
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.dismissWhenLeavingVisibleTop
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.messages.MessageForegroundService
import com.difft.android.chat.util.MessageNotificationUtil
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Critical Alerts page under notification settings. Do Not Disturb access pages are launched for
 * a result so the page refreshes on return; right after access is granted it offers the background
 * connection.
 */
@AndroidEntryPoint
class CriticalAlertSettingsFragment : Fragment(), SettingsIdiomPage {

    companion object {
        private const val KEY_AWAITING_DND_GRANT = "awaiting_dnd_grant"
    }

    @Inject
    lateinit var stateReader: NotificationSettingsStateReader

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    private var refreshJob: Job? = null
    private var uiState by mutableStateOf<NotificationSettingsUiState?>(null)

    /** Set when the access page was opened to grant (not to review or revoke) Do Not Disturb access; survives recreation. */
    private var awaitingDndGrant = false

    private val dndPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (view == null) return@registerForActivityResult
        refresh()
        if (awaitingDndGrant) {
            awaitingDndGrant = false
            offerBackgroundConnectionAfterGrant()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingDndGrant = savedInstanceState?.getBoolean(KEY_AWAITING_DND_GRANT) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AWAITING_DND_GRANT, awaitingDndGrant)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = ownsWindow()) {
                    Column(Modifier.fillMaxSize().detailPageInsets(containerPadsBottom())) {
                        TitleBar(
                            titleText = stringResource(R.string.critical_alerts),
                            onBackClick = { detailNavigation().pop(this@CriticalAlertSettingsFragment) },
                        )
                        CriticalAlertSettingsContent(
                            state = uiState,
                            actions = NotificationSubpageActions(
                                onFix = ::onFix,
                                onGuide = {
                                    requireActivity().openExternalBrowser(
                                        BackgroundConnectionHelpLink.build(BackgroundConnectionHelpLink.Source.NOTIFICATION_SETTINGS)
                                    )
                                },
                                onBackgroundConnection = {
                                    detailNavigation().push(this@CriticalAlertSettingsFragment, BackgroundConnectionSettingsDestination)
                                },
                            ),
                        )
                    }
                }
            }
        }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            stateReader.readOrNull(page = "critical")?.let { uiState = it }
        }
    }

    private fun onFix(fix: NotificationFix) {
        when (fix) {
            NotificationFix.DND_ACCESS, NotificationFix.DND_ACCESS_LIST -> {
                awaitingDndGrant = fix == NotificationFix.DND_ACCESS
                val opened = NotificationFixJump.openForResult(requireActivity(), fix) { dndPermissionLauncher.launch(it) }
                if (!opened) {
                    awaitingDndGrant = false
                    L.w { "[NotificationSettings] fix jump failed fix=$fix" }
                    ToastUtil.show(R.string.notification_settings_open_fail)
                }
            }
            else -> openNotificationFix(fix, stateReader)
        }
    }

    /**
     * Critical alerts rely on the background connection: right after access is granted, offer to turn it
     * on instead of leaving the user to find the row.
     */
    private fun offerBackgroundConnectionAfterGrant() {
        viewLifecycleOwner.lifecycleScope.launch {
            val granted = withContext(Dispatchers.IO) { messageNotificationUtil.isNotificationPolicyAccessGranted() }
            if (!granted || MessageForegroundService.isRunning || !isAdded) return@launch
            L.i { "[NotificationSettings] critical alerts granted, offering background connection" }
            val dialog = dismissWithView(
                ComposeDialogManager.showMessageDialog(
                    context = requireContext(),
                    title = getString(R.string.critical_alert_enable_background_connection),
                    message = getString(R.string.background_connection_prompt_critical),
                    confirmText = getString(R.string.critical_alert_enable_background_connection),
                    cancelText = getString(R.string.notification_ignore),
                    cancelable = false,
                    onConfirm = { detailNavigation().push(this@CriticalAlertSettingsFragment, BackgroundConnectionSettingsDestination) },
                ),
            )
            dismissWhenLeavingVisibleTop { dialog?.dismiss() }
        }
    }
}
