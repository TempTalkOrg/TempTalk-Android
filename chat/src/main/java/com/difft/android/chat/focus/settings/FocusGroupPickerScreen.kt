package com.difft.android.chat.focus.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.input.DifftSearchBar
import com.difft.android.base.ui.compose.settings.DifftSettingsCaption
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.common.compose.GroupAvatar
import com.difft.android.chat.focus.FocusGroupItem
import com.difft.android.base.R as BaseR

/** Stateless "Select Focus Group" page: Cancel / Done bar, search, single-select list, footer note. */
@Composable
fun FocusGroupPickerScreen(
    state: FocusGroupPickerContract.State,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        PickerBar(canConfirm = state.canConfirm, onCancel = onCancel, onDone = onDone)
        DifftSearchBar(
            query = state.query,
            onQueryChange = onQueryChange,
            onClear = { onQueryChange("") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = DifftSettingsDefaults.PageInset),
        )
        Spacer(Modifier.height(DifftSettingsDefaults.TitleBottomGap))
        LazyColumn(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = DifftSettingsDefaults.PageInset)
                .clip(DifftSettingsDefaults.GroupShape)
                .background(DifftTheme.colors.divider),
            verticalArrangement = Arrangement.spacedBy(DifftSettingsDefaults.SeparatorThickness),
        ) {
            items(state.groups, key = { it.gid }) { group ->
                GroupRow(group = group, selected = group.gid == state.selectedGid, onClick = { onSelect(group.gid) })
            }
        }
        if (!state.loading && state.groups.isEmpty()) {
            Text(
                text = stringResource(R.string.focus_picker_empty),
                style = DifftSettingsDefaults.LabelTextStyle,
                color = DifftTheme.colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
        }
        DifftSettingsCaption(
            text = stringResource(R.string.focus_picker_footer),
            modifier = Modifier.padding(
                horizontal = DifftSettingsDefaults.PageInset + DifftSettingsDefaults.CaptionInset,
                vertical = DifftSettingsDefaults.FooterTopGap,
            ),
        )
    }
}

@Composable
private fun PickerBar(canConfirm: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.focus_picker_cancel),
            style = DifftTheme.typography.p2,
            color = DifftTheme.colors.textPrimary,
            modifier = Modifier.clickable(onClick = onCancel),
        )
        Text(
            text = stringResource(R.string.focus_select_group),
            style = DifftTheme.typography.h4,
            color = DifftTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.focus_picker_done),
            style = DifftTheme.typography.p2,
            color = if (canConfirm) DifftTheme.colors.textInfo else DifftTheme.colors.textDisabled,
            modifier = Modifier.clickable(enabled = canConfirm, onClick = onDone),
        )
    }
}

@Composable
private fun GroupRow(group: FocusGroupItem, selected: Boolean, onClick: () -> Unit) {
    DifftSettingsRow(
        onClick = onClick,
        showChevron = false,
        minHeight = 64.dp,
        leading = {
            GroupAvatar(avatarData = group.avatar, gid = group.gid, size = DifftTheme.spacing.avatarSmall)
        },
        trailing = if (selected) {
            {
                Icon(
                    painter = painterResource(BaseR.drawable.base_ic_check),
                    contentDescription = null,
                    tint = DifftTheme.colors.icon,
                    modifier = Modifier.size(DifftSettingsDefaults.CheckmarkSize),
                )
            }
        } else {
            null
        },
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = group.name,
                style = DifftSettingsDefaults.LabelTextStyle,
                color = DifftTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = memberCountText(group.memberCount),
                style = DifftSettingsDefaults.CaptionTextStyle,
                color = DifftTheme.colors.textTertiary,
            )
        }
    }
}
