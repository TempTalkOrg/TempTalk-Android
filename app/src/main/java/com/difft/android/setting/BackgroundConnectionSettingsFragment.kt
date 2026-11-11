package com.difft.android.setting

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationFixJump
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.detailPageInsets
import com.difft.android.base.utils.pane.containerPadsBottom
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.messages.MessageForegroundService
import com.difft.android.chat.messages.MessageServiceManager
import com.difft.android.chat.util.AutoStartPermissionHelper
import com.difft.android.chat.util.BackgroundActivityPermissionHelper
import com.difft.android.chat.util.DeviceProperties
import com.difft.android.startup.prompt.StartupPromptStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.difft.android.chat.R as ChatR






@AndroidEntryPoint
class BackgroundConnectionSettingsFragment : Fragment(), SettingsIdiomPage {

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var messageServiceManager: MessageServiceManager

    @Inject
    lateinit var startupPromptStore: StartupPromptStore

    private var refreshTrigger = mutableIntStateOf(0)
    private val switchOn = mutableStateOf(false)
    private val highlightedRow = mutableStateOf<BgRow?>(null)

    
    private var startedFromSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startedFromSwitch = savedInstanceState?.getBoolean(KEY_STARTED_FROM_SWITCH) ?: false
        highlightedRow.value = savedInstanceState?.getString(KEY_HIGHLIGHTED_ROW)?.let { name -> BgRow.entries.firstOrNull { it.name == name } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_STARTED_FROM_SWITCH, startedFromSwitch)
        outState.putString(KEY_HIGHLIGHTED_ROW, highlightedRow.value?.name)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        switchOn.value = MessageForegroundService.isRunning
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DifftTheme(applyWindowBackground = ownsWindow()) {
                    Box(Modifier.detailPageInsets(containerPadsBottom())) { MainContent() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTrigger.intValue++
        switchOn.value = MessageForegroundService.isRunning
        handleReturnFromFix()
        
        viewLifecycleOwner.lifecycleScope.launch {
            delay(LATE_STATE_REFRESH_MS)
            refreshTrigger.intValue++
            handleReturnFromFix()
        }
    }

    @Composable
    private fun MainContent() {
        val trigger = refreshTrigger.intValue
        val brand = OemBrand.current()
        val check = remember(trigger) { readCheckState() }
        val flags = remember(trigger) { rowFlags(check) }
        val rows = remember(trigger) { backgroundRowOrder(brand, flags) }

        BackgroundConnectionPage(
            state = BackgroundConnectionPageState(
                isBackgroundConnectionEnabled = switchOn.value,
                brand = brand,
                rows = rows,
                check = check,
                highlightedRow = highlightedRow.value,
                scrollKey = trigger,
            ),
            onBackClick = { detailNavigation().pop(this@BackgroundConnectionSettingsFragment) },
            onSwitchChanged = { newValue -> if (newValue) onSwitchTurnedOn() else disableBackgroundConnection() },
            onRowClick = { row -> onRowClick(row, check) },
            onGuideClick = ::openSetupGuide,
        )
    }

    private fun rowFlags(check: BgCheckState) = BgRowFlags(
        showBackgroundRestriction = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
        canOpenAutoStart = AutoStartPermissionHelper.canOpenAutoStartSettings(requireContext()),
        canOpenBackgroundActivity = BackgroundActivityPermissionHelper.canOpenBackgroundActivitySettings(requireContext()),
        exactAlarmApplicable = check.exactAlarmState != ExactAlarmState.NotApplicable,
    )

    private fun readCheckState(): BgCheckState {
        val context = requireContext()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return BgCheckState(
            isBackgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && DeviceProperties.isBackgroundRestricted(context),
            isIgnoringBatteryOpt = powerManager.isIgnoringBatteryOptimizations(context.packageName),
            isDataSaverRestricted = DeviceProperties.getDataSaverState(context).isRestricted,
            exactAlarmState = ExactAlarmAccess.state(context),
        )
    }

    
    private fun onSwitchTurnedOn() {
        val failing = firstFailingRequirement(readCheckState())
        if (failing == null) {
            startedFromSwitch = false
            highlightedRow.value = null
            if (!enableBackgroundConnection()) switchOn.value = false
            return
        }
        L.i { "[BgConnection] switch redirected requirement=${failing.name}" }
        startedFromSwitch = true
        highlightedRow.value = failing.row
        switchOn.value = false
        ToastUtil.showLong(getString(ChatR.string.background_connection_fix_first, getString(titleFor(failing.row))))
        when (failing) {
            BgRequirement.BACKGROUND_RESTRICTION -> openBackgroundRestrictionSettings()
            BgRequirement.BATTERY_OPTIMIZATION -> openBatteryOptimizationSettings()
            BgRequirement.DATA_SAVER -> openDataSaverSettings()
        }
    }

    
    private fun handleReturnFromFix() {
        if (!startedFromSwitch || !isAdded) return
        val failing = firstFailingRequirement(readCheckState())
        when (val action = returnAction(startedFromSwitch, switchOn.value, failing)) {
            ReturnAction.None -> if (switchOn.value) startedFromSwitch = false
            ReturnAction.AutoEnable -> {
                L.i { "[BgConnection] requirements fixed, enabling from switch" }
                startedFromSwitch = false
                highlightedRow.value = null
                enableBackgroundConnection()
            }
            is ReturnAction.Highlight -> highlightedRow.value = action.requirement.row
        }
    }

    
    private fun enableBackgroundConnection(): Boolean {
        if (!messageServiceManager.checkBackgroundConnectionRequirements()) {
            L.w { "[BgConnection] requirements not met at enable" }
            return false
        }
        userManager.update { autoStartMessageService = true }
        L.i { "[MessageService] User enabled service, autoStartMessageService set to true" }
        recordSwitch(on = true)
        messageServiceManager.startService()
        switchOn.value = true
        return true
    }

    private fun disableBackgroundConnection() {
        startedFromSwitch = false
        highlightedRow.value = null
        userManager.update { autoStartMessageService = false }
        L.i { "[MessageService] User disabled service, autoStartMessageService set to false" }
        recordSwitch(on = false)
        messageServiceManager.stopService()
        switchOn.value = false
    }

    
    private fun recordSwitch(on: Boolean) {
        appScope.launch {
            runCatching { startupPromptStore.recordBackgroundConnectionSwitch(on, ServerTimeProvider.nowMillis()) }
                .onFailure { L.w { "[BgConnection] switch history not saved error=${it.javaClass.simpleName}" } }
        }
    }

    private fun onRowClick(row: BgRow, check: BgCheckState) {
        when (row) {
            BgRow.BACKGROUND_RESTRICTION -> openBackgroundRestrictionSettings()
            BgRow.BATTERY_OPTIMIZATION -> if (!check.isIgnoringBatteryOpt) openBatteryOptimizationSettings()
            BgRow.DATA_SAVER -> openDataSaverSettings()
            BgRow.EXACT_ALARM -> ExactAlarmAccess.openSettings(requireActivity())
            BgRow.AUTO_START -> {
                if (!AutoStartPermissionHelper.openAutoStartSettings(requireActivity())) L.w { "[BgConnection] open auto-start settings failed" }
            }
            BgRow.BACKGROUND_ACTIVITY, BgRow.BACKGROUND_POWER -> {
                if (!BackgroundActivityPermissionHelper.openBackgroundActivitySettings(requireActivity())) {
                    L.w { "[BgConnection] open background-activity settings failed" }
                }
            }
            BgRow.VENDOR_GUIDE -> openSetupGuide()
        }
    }

    private fun openSetupGuide() {
        requireActivity().openExternalBrowser(BackgroundConnectionHelpLink.build(BackgroundConnectionHelpLink.Source.BACKGROUND_CONNECTION))
    }

    private fun openBackgroundRestrictionSettings() {
        if (!NotificationFixJump.open(requireActivity(), NotificationFix.BACKGROUND_RESTRICTION)) {
            L.w { "[BgConnection] open background restriction settings failed" }
        }
    }

    
    @SuppressLint("BatteryLife")
    private fun openBatteryOptimizationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                },
            )
        } catch (e: Exception) {
            L.e { "[BgConnection] settings_failed kind=battery error=${e.stackTraceToString()}" }
        }
    }

    private fun openDataSaverSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                },
            )
        } catch (e: Exception) {
            L.e { "[BgConnection] settings_failed kind=data_saver error=${e.stackTraceToString()}" }
        }
    }

    private companion object {
        const val LATE_STATE_REFRESH_MS = 800L
        const val KEY_STARTED_FROM_SWITCH = "bg_started_from_switch"
        const val KEY_HIGHLIGHTED_ROW = "bg_highlighted_row"
    }
}
