package com.difft.android.chat.group

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSwitchRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.chat.R

/**
 * Stateless body of the per-group notification settings screen.
 *
 * A plain scrolling [Column], not a LazyColumn: the option list is the three
 * [GlobalNotificationType] entries, not data.
 */
@Composable
fun GroupNotificationSettingsContent(
    globalNotificationType: Int,
    isDefaultEnabled: Boolean,
    currentType: Int,
    onDefaultSettingClick: () -> Unit,
    onSwitchChanged: (Boolean) -> Unit,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DifftSettingsDefaults.PageInset),
    ) {
        Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))

        DifftSettingsGroup(
            footer = {
                // The explanation and the shortcut to the global page are only meaningful while the
                // group actually follows the global setting.
                if (isDefaultEnabled) {
                    DifftSettingsCaption(
                        text = stringResource(R.string.notification_change_default_setting_tip),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.notification_update_default_group_setting),
                        style = DifftSettingsDefaults.CaptionTextStyle,
                        color = DifftTheme.colors.textInfo,
                        modifier = Modifier.clickable { onDefaultSettingClick() },
                    )
                }
            },
        ) {
            DifftSwitchRow(
                checked = isDefaultEnabled,
                onCheckedChange = onSwitchChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DifftSettingsDefaults.RowSurfaceColor),
                contentPadding = DifftSettingsDefaults.RowContentPadding,
            ) {
                Text(
                    text = stringResource(R.string.notification_default_setting),
                    style = DifftSettingsDefaults.LabelTextStyle,
                    color = DifftTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(DifftSettingsDefaults.TrailingGap))
                Text(
                    text = stringResource(globalNotificationLabelRes(globalNotificationType)),
                    style = DifftSettingsDefaults.LabelTextStyle,
                    color = DifftTheme.colors.textTertiary,
                    maxLines = 1,
                )
            }
        }

        if (!isDefaultEnabled) {
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
            DifftSettingsGroup(title = stringResource(R.string.notification_custom_settings)) {
                GlobalNotificationOptionRows(currentType, onOptionSelected)
            }
        }

        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GroupNotificationSettingsContentPreview() {
    DifftTheme(applyWindowBackground = false) {
        Column(Modifier.background(DifftTheme.colors.bg).size(360.dp, 520.dp)) {
            GroupNotificationSettingsContent(
                globalNotificationType = GlobalNotificationType.MENTION.value,
                isDefaultEnabled = false,
                currentType = GlobalNotificationType.MENTION.value,
                onDefaultSettingClick = {},
                onSwitchChanged = {},
                onOptionSelected = {},
            )
        }
    }
}
