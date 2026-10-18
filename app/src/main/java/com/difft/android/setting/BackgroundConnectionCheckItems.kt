package com.difft.android.setting

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R as ChatR

/**
 * The check rows under the background-connection switch.
 *
 * Rows that can read system state show it; the OEM auto-start and background-activity rows
 * cannot (no API exists), so they only offer an entry point and say "check".
 */
@Preview(showBackground = true)
@Composable
internal fun CheckItemsContent(
    isBackgroundRestricted: Boolean = true,
    isIgnoringBatteryOpt: Boolean = false,
    isDataSaverRestricted: Boolean = false,
    canOpenAutoStart: Boolean = true,
    canOpenBackgroundActivity: Boolean = true,
    showVendorGuide: Boolean = false,
    exactAlarmState: ExactAlarmState = ExactAlarmState.NotApplicable,
    onBackgroundRestrictionClick: (() -> Unit)? = null,
    onBatteryOptimizationClick: (() -> Unit)? = null,
    onDataSaverClick: (() -> Unit)? = null,
    onAutoStartClick: (() -> Unit)? = null,
    onBackgroundActivityClick: (() -> Unit)? = null,
    onVendorGuideClick: (() -> Unit)? = null,
    onExactAlarmClick: (() -> Unit)? = null,
) {
    Column {
        // Background restriction check (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DividerLine()
            CheckItem(
                title = stringResource(ChatR.string.background_restriction_check),
                status = if (isBackgroundRestricted) {
                    stringResource(ChatR.string.background_restriction_restricted)
                } else {
                    stringResource(ChatR.string.background_restriction_unrestricted)
                },
                showArrow = true,
                onClick = onBackgroundRestrictionClick
            )
        }

        // Battery optimization check
        DividerLine()
        CheckItem(
            title = stringResource(ChatR.string.battery_optimization_check),
            status = if (isIgnoringBatteryOpt) {
                stringResource(ChatR.string.battery_optimization_ignored)
            } else {
                stringResource(ChatR.string.battery_optimization_not_ignored)
            },
            showArrow = !isIgnoringBatteryOpt,
            onClick = if (!isIgnoringBatteryOpt) onBatteryOptimizationClick else null
        )

        // Data saver check
        DividerLine()
        CheckItem(
            title = stringResource(ChatR.string.data_saver_check),
            status = if (isDataSaverRestricted) {
                stringResource(ChatR.string.data_saver_restricted)
            } else {
                stringResource(ChatR.string.data_saver_unrestricted)
            },
            showArrow = true,
            onClick = onDataSaverClick
        )

        // OEM auto-start (state unreadable; entry only)
        if (canOpenAutoStart) {
            DividerLine()
            CheckItem(
                title = stringResource(ChatR.string.auto_start_check),
                status = stringResource(ChatR.string.auto_start_need_check),
                showArrow = true,
                onClick = onAutoStartClick
            )
        }

        // OEM background activity / power saving (separate switch from auto-start; state unreadable)
        if (canOpenBackgroundActivity) {
            DividerLine()
            CheckItem(
                title = stringResource(ChatR.string.background_activity_check),
                status = stringResource(ChatR.string.auto_start_need_check),
                showArrow = true,
                onClick = onBackgroundActivityClick
            )
        }

        // Other China skins: their switches move between versions, so the setup guide covers them.
        if (showVendorGuide) {
            DividerLine()
            CheckItem(
                title = stringResource(ChatR.string.background_vendor_settings),
                status = stringResource(ChatR.string.auto_start_need_check),
                showArrow = true,
                onClick = onVendorGuideClick
            )
        }

        // Exact alarm check (Android 12+, only when this build declares the permission)
        if (exactAlarmState != ExactAlarmState.NotApplicable) {
            val granted = exactAlarmState == ExactAlarmState.Granted
            DividerLine()
            CheckItem(
                title = stringResource(ChatR.string.exact_alarm_check),
                status = if (granted) {
                    stringResource(ChatR.string.exact_alarm_granted)
                } else {
                    stringResource(ChatR.string.exact_alarm_not_granted)
                },
                showArrow = !granted,
                onClick = if (!granted) onExactAlarmClick else null
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun CheckItem(
    title: String = "Background Restriction",
    status: String = "Restricted",
    showArrow: Boolean = true,
    onClick: (() -> Unit)? = null
) {
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
            style = DifftTheme.typography.p2,
            color = DifftTheme.colors.textPrimary,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = status,
            style = DifftTheme.typography.p2,
            color = DifftTheme.colors.textSecondary,
            modifier = Modifier.padding(end = 5.dp)
        )

        // Arrow - use alpha to hide but preserve space
        Box(
            modifier = Modifier.size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                imageVector = ImageVector.vectorResource(id = R.drawable.base_ic_chevron_right),
                contentDescription = null, // Decorative icon, no accessibility description needed
                colorFilter = ColorFilter.tint(DifftTheme.colors.icon),
                modifier = Modifier
                    .size(16.dp)
                    .alpha(if (showArrow) 1f else 0f)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun DividerLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                DifftTheme.colors.bg
            )
    )
}
