package com.difft.android.chat.focus

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.focus.settings.StillNotifyItem
import com.difft.android.chat.focus.settings.stillNotifySummary


enum class FocusDuration(val seconds: Int?, @param:StringRes val labelRes: Int) {
    ONE_HOUR(3600, R.string.focus_duration_1h),
    EIGHT_HOURS(28800, R.string.focus_duration_8h),
    MANUAL(null, R.string.focus_duration_manual),
}


data class FocusSheetDescription(@param:StringRes val res: Int, val stillNotify: List<StillNotifyItem>)


fun focusSheetDescription(focus: EffectiveFocus): FocusSheetDescription {
    val items = buildList {
        if (focus.othersAllowMentionMe) add(StillNotifyItem.MENTION_ME)
        if (focus.othersAllowMentionAll) add(StillNotifyItem.MENTION_ALL)
        if (focus.othersAllowGroupCall) add(StillNotifyItem.GROUP_CALL)
    }
    val res = when {
        focus.foldOthers && items.isNotEmpty() -> R.string.focus_sheet_desc_fold_notify
        items.isNotEmpty() -> R.string.focus_sheet_desc_notify
        focus.foldOthers -> R.string.focus_sheet_desc_fold
        else -> R.string.focus_sheet_desc_plain
    }
    return FocusSheetDescription(res, items)
}


@Composable
fun FocusDurationSheet(
    groupName: String,
    description: FocusSheetDescription,
    onSelect: (FocusDuration) -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            text = stringResource(R.string.focus_sheet_title, groupName),
            style = DifftTheme.typography.h4,
            color = DifftTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Text(
            text = if (description.stillNotify.isEmpty()) {
                stringResource(description.res)
            } else {
                stringResource(description.res, stillNotifySummary(description.stillNotify))
            },
            style = DifftTheme.typography.p4,
            color = DifftTheme.colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
        FocusDuration.entries.forEach { option ->
            SheetRow(stringResource(option.labelRes), DifftTheme.colors.primary) { onSelect(option) }
        }
        SheetRow(stringResource(R.string.focus_cancel), DifftTheme.colors.textPrimary, onCancel)
    }
}

@Composable
private fun SheetRow(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = color,
        style = DifftTheme.typography.p2,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    )
}
