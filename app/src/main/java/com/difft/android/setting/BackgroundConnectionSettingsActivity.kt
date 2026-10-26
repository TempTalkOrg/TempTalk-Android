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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.difft.android.base.BaseActivity
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationFixJump
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.settings.DifftSwitchRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.base.widget.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import com.difft.android.chat.messages.MessageForegroundService
import com.difft.android.chat.messages.MessageServiceManager
import com.difft.android.chat.util.AutoStartPermissionHelper
import com.difft.android.chat.util.BackgroundActivityPermissionHelper
import com.difft.android.chat.util.DeviceProperties
import javax.inject.Inject

@AndroidEntryPoint
class BackgroundConnectionSettingsActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, BackgroundConnectionSettingsActivity::class.java)
            activity.startActivity(intent)
        }
    }

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var messageServiceManager: MessageServiceManager

    private var refreshTrigger = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val composeView = ComposeView(this)
        composeView.setContent {
            DifftTheme {
                MainContent()
            }
        }
        setContentView(composeView)
    }

    override fun onResume() {
        super.onResume()
        // Trigger recomposition to refresh system states
        refreshTrigger.intValue++
    }

    @Preview
    @Composable
    private fun MainContent() {
        // Watch refresh trigger to update system states
        val trigger = refreshTrigger.intValue

        var isBackgroundConnectionEnabled by remember(trigger) {
            mutableStateOf(MessageForegroundService.isRunning)
        }

        // Get system states - refresh when trigger changes
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBatteryOpt = remember(trigger) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        }

        val isBackgroundRestricted = remember(trigger) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DeviceProperties.isBackgroundRestricted(this)
            } else {
                false
            }
        }

        val dataSaverState = remember(trigger) {
            DeviceProperties.getDataSaverState(this)
        }
        val canOpenAutoStart = remember(trigger) {
            AutoStartPermissionHelper.canOpenAutoStartSettings()
        }
        val showVendorGuide = remember { OemBrand.current().let { it.isChinaOem && it != OemBrand.XIAOMI } }
        val canOpenBackgroundActivity = remember(trigger) {
            BackgroundActivityPermissionHelper.canOpenBackgroundActivitySettings(this)
        }

        val exactAlarmState = remember(trigger) {
            ExactAlarmAccess.state(this@BackgroundConnectionSettingsActivity)
        }

        MainContentPreview(
            isBackgroundConnectionEnabled = isBackgroundConnectionEnabled,
            isIgnoringBatteryOpt = isIgnoringBatteryOpt,
            isBackgroundRestricted = isBackgroundRestricted,
            isDataSaverRestricted = dataSaverState.isRestricted,
            canOpenAutoStart = canOpenAutoStart,
            canOpenBackgroundActivity = canOpenBackgroundActivity,
            showVendorGuide = showVendorGuide,
            exactAlarmState = exactAlarmState,
            onBackgroundConnectionSwitchChanged = { newValue ->
                if (newValue) {
                    // Enable service: check requirements first
                    if (!messageServiceManager.checkBackgroundConnectionRequirements()) {
                        L.w { "Background connection requirements not met" }
                        ToastUtil.showLong(getString(com.difft.android.chat.R.string.background_connection_check_failed))
                        // Reset switch to off state
                        isBackgroundConnectionEnabled = false
                        return@MainContentPreview
                    }

                    userManager.update { autoStartMessageService = true }
                    L.i { "[MessageService] User enabled service, autoStartMessageService set to true" }

                    messageServiceManager.startService()
                    isBackgroundConnectionEnabled = true
                } else {
                    // Disable service
                    userManager.update { autoStartMessageService = false }
                    L.i { "[MessageService] User disabled service, autoStartMessageService set to false" }

                    messageServiceManager.stopService()
                    isBackgroundConnectionEnabled = false
                }
            },
            onBackgroundRestrictionClick = { openBackgroundRestrictionSettings() },
            onBatteryOptimizationClick = { openBatteryOptimizationSettings() },
            onDataSaverClick = { openDataSaverSettings() },
            onAutoStartClick = {
                val success = AutoStartPermissionHelper.openAutoStartSettings(this@BackgroundConnectionSettingsActivity)
                if (!success) {
                    L.w { "Failed to open auto-start settings" }
                }
            },
            onBackgroundActivityClick = {
                val success = BackgroundActivityPermissionHelper.openBackgroundActivitySettings(this@BackgroundConnectionSettingsActivity)
                if (!success) {
                    L.w { "[BgConnection] open background-activity settings failed" }
                }
            },
            onVendorGuideClick = { openExternalBrowser(BackgroundConnectionHelpLink.build(BackgroundConnectionHelpLink.Source.BACKGROUND_CONNECTION)) },
            onExactAlarmClick = { ExactAlarmAccess.openSettings(this) },
            onSetupGuideClick = { openExternalBrowser(BackgroundConnectionHelpLink.build(BackgroundConnectionHelpLink.Source.BACKGROUND_CONNECTION)) }
        )
    }

    /** Standalone preview: owns the switch state the production caller normally supplies. */
    @Preview(showBackground = true, backgroundColor = 0xFFF5F5F5)
    @Composable
    private fun MainContentPreviewHost() {
        var switchState by remember { mutableStateOf(false) }
        MainContentPreview(
            isBackgroundConnectionEnabled = switchState,
            onBackgroundConnectionSwitchChanged = { switchState = it },
        )
    }

    @Composable
    private fun MainContentPreview(
        isBackgroundConnectionEnabled: Boolean = false,
        isIgnoringBatteryOpt: Boolean = false,
        isBackgroundRestricted: Boolean = true,
        isDataSaverRestricted: Boolean = false,
        canOpenAutoStart: Boolean = true,
        canOpenBackgroundActivity: Boolean = true,
        showVendorGuide: Boolean = false,
        exactAlarmState: ExactAlarmState = ExactAlarmState.NotApplicable,
        onBackgroundConnectionSwitchChanged: (Boolean) -> Unit,
        onBackgroundRestrictionClick: (() -> Unit)? = null,
        onBatteryOptimizationClick: (() -> Unit)? = null,
        onDataSaverClick: (() -> Unit)? = null,
        onAutoStartClick: (() -> Unit)? = null,
        onBackgroundActivityClick: (() -> Unit)? = null,
        onVendorGuideClick: (() -> Unit)? = null,
        onExactAlarmClick: (() -> Unit)? = null,
        onSetupGuideClick: (() -> Unit)? = null
    ) {
        val context = LocalContext.current

        Column(
            Modifier.fillMaxSize().systemBarsPadding()
        ) {
            TitleBar(
                titleText = stringResource(com.difft.android.chat.R.string.background_connection),
                onBackClick = {
                    (context as? BackgroundConnectionSettingsActivity)?.onBackPressedDispatcher?.onBackPressed()
                }
            )

            LazyColumn(modifier = Modifier.padding(16.dp)) {
                // Combined switch and check items section (no spacing)
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color(
                                    ContextCompat.getColor(
                                        context,
                                        R.color.bg_elevated
                                    )
                                ),
                                DifftTheme.radius.largeShape
                            )
                    ) {
                        // Main switch item
                        BackgroundConnectionSwitchItem(
                            isEnabled = isBackgroundConnectionEnabled,
                            onSwitchChanged = onBackgroundConnectionSwitchChanged
                        )

                        // Check items
                        CheckItemsContent(
                            isBackgroundRestricted = isBackgroundRestricted,
                            isIgnoringBatteryOpt = isIgnoringBatteryOpt,
                            isDataSaverRestricted = isDataSaverRestricted,
                            canOpenAutoStart = canOpenAutoStart,
                            canOpenBackgroundActivity = canOpenBackgroundActivity,
                            showVendorGuide = showVendorGuide,
                            exactAlarmState = exactAlarmState,
                            onBackgroundRestrictionClick = onBackgroundRestrictionClick,
                            onBatteryOptimizationClick = onBatteryOptimizationClick,
                            onDataSaverClick = onDataSaverClick,
                            onAutoStartClick = onAutoStartClick,
                            onBackgroundActivityClick = onBackgroundActivityClick,
                            onVendorGuideClick = onVendorGuideClick,
                            onExactAlarmClick = onExactAlarmClick
                        )
                    }
                }

                // Bottom tips text
                item {
                    BottomTipsText()
                }

                if (onSetupGuideClick != null) {
                    item {
                        SetupGuideFooter(
                            onClick = onSetupGuideClick,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            prompt = com.difft.android.chat.R.string.background_connection_guide_check,
                        )
                    }
                }
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    private fun BottomTipsText() {
        val context = LocalContext.current
        Text(
            text = stringResource(com.difft.android.chat.R.string.background_connection_tips),
            style = DifftTheme.typography.p3,
            color = Color(
                ContextCompat.getColor(
                    context,
                    R.color.t_secondary
                )
            ),
            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)
        )
    }

    @Preview(showBackground = true)
    @Composable
    private fun BackgroundConnectionSwitchItem(
        isEnabled: Boolean = false,
        onSwitchChanged: (Boolean) -> Unit = {}
    ) {
        DifftSwitchRow(
            label = stringResource(com.difft.android.chat.R.string.background_connection),
            checked = isEnabled,
            onCheckedChange = onSwitchChanged,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        )
    }

    private fun openBackgroundRestrictionSettings() {
        if (!NotificationFixJump.open(this, NotificationFix.BACKGROUND_RESTRICTION)) {
            L.w { "[BgConnection] open background restriction settings failed" }
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
