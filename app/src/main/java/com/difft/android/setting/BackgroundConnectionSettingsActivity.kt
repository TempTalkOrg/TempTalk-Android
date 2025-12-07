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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.core.content.ContextCompat
import com.difft.android.base.BaseActivity
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import com.difft.android.base.widget.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.messages.MessageForegroundService
import org.thoughtcrime.securesms.messages.MessageServiceManager
import org.thoughtcrime.securesms.util.AutoStartPermissionHelper
import org.thoughtcrime.securesms.util.DeviceProperties
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
            DifftTheme(useSecondaryBackground = true) {
                MainContent()
            }
        }
        setContentView(composeView)
    }

    override fun onResume() {
        super.onResume()
        // Trigger recomposition to refresh system states
        refreshTrigger.value++
    }

    @Preview
    @Composable
    private fun MainContent() {
        // Watch refresh trigger to update system states
        val trigger = refreshTrigger.value

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
            AutoStartPermissionHelper.canOpenAutoStartSettings(this)
        }

        val hasExactAlarmPermission = remember(trigger) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                alarmManager.canScheduleExactAlarms()
            } else {
                true // Android 12 以下不需要权限
            }
        }

        MainContentPreview(
            isBackgroundConnectionEnabled = isBackgroundConnectionEnabled,
            isIgnoringBatteryOpt = isIgnoringBatteryOpt,
            isBackgroundRestricted = isBackgroundRestricted,
            isDataSaverRestricted = dataSaverState.isRestricted,
            canOpenAutoStart = canOpenAutoStart,
            hasExactAlarmPermission = hasExactAlarmPermission,
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
            onExactAlarmClick = { openExactAlarmSettings() }
        )
    }

    @Preview(showBackground = true, backgroundColor = 0xFFF5F5F5)
    @Composable
    private fun MainContentPreview(
        isBackgroundConnectionEnabled: Boolean = false,
        isIgnoringBatteryOpt: Boolean = false,
        isBackgroundRestricted: Boolean = true,
        isDataSaverRestricted: Boolean = false,
        canOpenAutoStart: Boolean = true,
        hasExactAlarmPermission: Boolean = false,
        onBackgroundConnectionSwitchChanged: ((Boolean) -> Unit)? = null,
        onBackgroundRestrictionClick: (() -> Unit)? = null,
        onBatteryOptimizationClick: (() -> Unit)? = null,
        onDataSaverClick: (() -> Unit)? = null,
        onAutoStartClick: (() -> Unit)? = null,
        onExactAlarmClick: (() -> Unit)? = null
    ) {
        // Local switch state that can be toggled
        var switchState by remember { mutableStateOf(isBackgroundConnectionEnabled) }
        val context = LocalContext.current

        // Sync switchState with isBackgroundConnectionEnabled
        LaunchedEffect(isBackgroundConnectionEnabled) {
            switchState = isBackgroundConnectionEnabled
        }

        Column(
            Modifier.fillMaxSize()
        ) {
            TitleBar(
                titleText = context.getString(com.difft.android.chat.R.string.background_connection),
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
                                        R.color.bg_setting_item
                                    )
                                ),
                                RoundedCornerShape(8.dp)
                            )
                    ) {
                        // Main switch item
                        BackgroundConnectionSwitchItem(
                            isEnabled = switchState,
                            onSwitchChanged = { newValue ->
                                onBackgroundConnectionSwitchChanged?.invoke(newValue)
                                // Only update local state if callback didn't handle it
                                // The callback will update switchState based on actual success/failure
                            }
                        )

                        // Check items
                        CheckItemsContent(
                            isBackgroundRestricted = isBackgroundRestricted,
                            isIgnoringBatteryOpt = isIgnoringBatteryOpt,
                            isDataSaverRestricted = isDataSaverRestricted,
                            canOpenAutoStart = canOpenAutoStart,
                            hasExactAlarmPermission = hasExactAlarmPermission,
                            onBackgroundRestrictionClick = onBackgroundRestrictionClick,
                            onBatteryOptimizationClick = onBatteryOptimizationClick,
                            onDataSaverClick = onDataSaverClick,
                            onAutoStartClick = onAutoStartClick,
                            onExactAlarmClick = onExactAlarmClick
                        )
                    }
                }

                // Bottom tips text
                item {
                    BottomTipsText()
                }
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    private fun CheckItemsContent(
        isBackgroundRestricted: Boolean = true,
        isIgnoringBatteryOpt: Boolean = false,
        isDataSaverRestricted: Boolean = false,
        canOpenAutoStart: Boolean = true,
        hasExactAlarmPermission: Boolean = false,
        onBackgroundRestrictionClick: (() -> Unit)? = null,
        onBatteryOptimizationClick: (() -> Unit)? = null,
        onDataSaverClick: (() -> Unit)? = null,
        onAutoStartClick: (() -> Unit)? = null,
        onExactAlarmClick: (() -> Unit)? = null
    ) {
        val context = LocalContext.current
        Column {
            // Background restriction check (Android 9+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DividerLine()
                CheckItem(
                    title = context.getString(com.difft.android.chat.R.string.background_restriction_check),
                    status = if (isBackgroundRestricted) {
                        context.getString(com.difft.android.chat.R.string.background_restriction_restricted)
                    } else {
                        context.getString(com.difft.android.chat.R.string.background_restriction_unrestricted)
                    },
                    showArrow = true,
                    onClick = onBackgroundRestrictionClick
                )
            }

            // Battery optimization check
            DividerLine()
            CheckItem(
                title = context.getString(com.difft.android.chat.R.string.battery_optimization_check),
                status = if (isIgnoringBatteryOpt) {
                    context.getString(com.difft.android.chat.R.string.battery_optimization_ignored)
                } else {
                    context.getString(com.difft.android.chat.R.string.battery_optimization_not_ignored)
                },
                showArrow = !isIgnoringBatteryOpt,
                onClick = if (!isIgnoringBatteryOpt) onBatteryOptimizationClick else null
            )

            // Data saver check
            DividerLine()
            CheckItem(
                title = context.getString(com.difft.android.chat.R.string.data_saver_check),
                status = if (isDataSaverRestricted) {
                    context.getString(com.difft.android.chat.R.string.data_saver_restricted)
                } else {
                    context.getString(com.difft.android.chat.R.string.data_saver_unrestricted)
                },
                showArrow = true,
                onClick = onDataSaverClick
            )

            // Auto start check (for Chinese ROMs)
            if (canOpenAutoStart) {
                DividerLine()
                CheckItem(
                    title = context.getString(com.difft.android.chat.R.string.auto_start_check),
                    status = context.getString(com.difft.android.chat.R.string.auto_start_need_check),
                    showArrow = true,
                    onClick = onAutoStartClick
                )
            }

            // Exact alarm check (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                DividerLine()
                CheckItem(
                    title = context.getString(com.difft.android.chat.R.string.exact_alarm_check),
                    status = if (hasExactAlarmPermission) {
                        context.getString(com.difft.android.chat.R.string.exact_alarm_granted)
                    } else {
                        context.getString(com.difft.android.chat.R.string.exact_alarm_not_granted)
                    },
                    showArrow = !hasExactAlarmPermission,
                    onClick = if (!hasExactAlarmPermission) onExactAlarmClick else null
                )
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    private fun BottomTipsText() {
        val context = LocalContext.current
        Text(
            text = context.getString(com.difft.android.chat.R.string.background_connection_tips),
            fontSize = 14.sp,
            color = Color(
                ContextCompat.getColor(
                    context,
                    R.color.t_secondary
                )
            ),
            modifier = Modifier.padding(top = 16.dp)
        )
    }

    @Preview(showBackground = true)
    @Composable
    private fun BackgroundConnectionSwitchItem(
        isEnabled: Boolean = false,
        onSwitchChanged: (Boolean) -> Unit = {}
    ) {
        val context = LocalContext.current
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = context.getString(com.difft.android.chat.R.string.background_connection),
                fontSize = 16.sp,
                color = Color(
                    ContextCompat.getColor(
                        context,
                        R.color.t_primary
                    )
                ),
                modifier = Modifier.weight(1f)
            )

            Switch(
                checked = isEnabled,
                onCheckedChange = onSwitchChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colorResource(id = R.color.t_white),
                    checkedTrackColor = colorResource(id = R.color.primary),
                    uncheckedThumbColor = colorResource(id = R.color.t_white),
                    uncheckedTrackColor = colorResource(id = R.color.gray_600)
                )
            )
        }
    }

    @Preview(showBackground = true)
    @Composable
    private fun CheckItem(
        title: String = "Background Restriction",
        status: String = "Restricted",
        showArrow: Boolean = true,
        onClick: (() -> Unit)? = null
    ) {
        val context = LocalContext.current
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable(enabled = onClick != null) {
                    onClick?.invoke()
                }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = Color(
                    ContextCompat.getColor(
                        context,
                        R.color.t_primary
                    )
                ),
                modifier = Modifier.weight(1f)
            )

            Text(
                text = status,
                fontSize = 16.sp,
                color = Color(
                    ContextCompat.getColor(
                        context,
                        R.color.t_secondary
                    )
                ),
                modifier = Modifier.padding(end = 5.dp)
            )

            // Arrow - use alpha to hide but preserve space
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    imageVector = ImageVector.vectorResource(id = com.difft.android.chat.R.drawable.chat_ic_arrow_right),
                    contentDescription = null, // Decorative icon, no accessibility description needed
                    colorFilter = ColorFilter.tint(
                        Color(
                            ContextCompat.getColor(
                                context,
                                R.color.t_primary
                            )
                        )
                    ),
                    modifier = Modifier
                        .size(20.dp)
                        .alpha(if (showArrow) 1f else 0f)
                )
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    private fun DividerLine() {
        val context = LocalContext.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Color(
                        ContextCompat.getColor(
                            context,
                            R.color.bg_setting
                        )
                    )
                )
        )
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

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } catch (e: Exception) {
                L.e(e) { "Failed to open exact alarm settings" }
            }
        }
    }
}
