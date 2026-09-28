package com.difft.android.chat.group

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.difft.android.base.ui.compose.settings.DifftSettingsOptionRow
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.chat.R

/**
 * The three [GlobalNotificationType] option rows, shared by the global notification page and the
 * per-group page so both render one implementation. MENTION is the only option carrying an
 * explanation line.
 */
@Composable
internal fun GlobalNotificationOptionRows(selected: Int, onSelect: (Int) -> Unit) {
    GlobalNotificationType.entries.forEach { entry ->
        DifftSettingsOptionRow(
            label = stringResource(globalNotificationLabelRes(entry.value)),
            selected = selected == entry.value,
            onClick = { onSelect(entry.value) },
            description = if (entry == GlobalNotificationType.MENTION) {
                stringResource(R.string.notification_mention_only_description)
            } else {
                null
            },
        )
    }
}

/** Label for a [GlobalNotificationType] value; an unknown value reads as "all". */
internal fun globalNotificationLabelRes(value: Int): Int = when (value) {
    GlobalNotificationType.MENTION.value -> R.string.notification_mention_only
    GlobalNotificationType.OFF.value -> R.string.notification_off
    else -> R.string.notification_all
}
