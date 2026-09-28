package com.difft.android.setting

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsOptionRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.NotificationContentDisplayType
import com.difft.android.chat.R

/** Label for a [NotificationContentDisplayType] value; an unknown value reads as name + content. */
private fun notificationContentLabelRes(value: Int): Int = when (value) {
    NotificationContentDisplayType.NAME_ONLY.value -> R.string.notification_only_name
    NotificationContentDisplayType.NO_NAME_OR_CONTENT.value -> R.string.notification_no_name_or_content
    else -> R.string.notification_display_name_and_content
}

/**
 * Stateless body of the notification-content-display screen.
 *
 * Every tap reports through [onSelect], including a tap on the already-selected row: the host
 * closes the screen on any tap, so suppressing the unchanged case would strand the user here.
 */
@Composable
fun NotificationContentDisplaySettingsContent(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DifftSettingsDefaults.PageInset),
    ) {
        Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
        DifftSettingsGroup {
            NotificationContentDisplayType.entries.forEach { entry ->
                DifftSettingsOptionRow(
                    label = stringResource(notificationContentLabelRes(entry.value)),
                    selected = selected == entry.value,
                    onClick = { onSelect(entry.value) },
                )
            }
        }
        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NotificationContentDisplaySettingsContentPreview() {
    DifftTheme(applyWindowBackground = false) {
        Column(Modifier.background(DifftTheme.colors.bg).size(360.dp, 320.dp)) {
            NotificationContentDisplaySettingsContent(
                selected = NotificationContentDisplayType.NAME_AND_CONTENT.value,
                onSelect = {},
            )
        }
    }
}
