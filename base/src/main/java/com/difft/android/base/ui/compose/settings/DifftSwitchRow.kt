package com.difft.android.base.ui.compose.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.DifftSwitch
import com.difft.android.base.ui.theme.DifftTheme



























@Composable
fun DifftSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    switchAppearsEnabled: Boolean = enabled,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    minHeight: Dp = DifftSettingsDefaults.RowMinHeight,
) = DifftSwitchRow(
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier,
    enabled = enabled,
    switchAppearsEnabled = switchAppearsEnabled,
    contentPadding = contentPadding,
    minHeight = minHeight,
) {
    Text(
        text = label,
        style = DifftSettingsDefaults.LabelTextStyle,
        color = DifftTheme.colors.textPrimary,
        modifier = Modifier.weight(1f),
    )
}


@Composable
fun DifftSwitchRow(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    switchAppearsEnabled: Boolean = enabled,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    minHeight: Dp = DifftSettingsDefaults.RowMinHeight,
    label: @Composable RowScope.() -> Unit,
) {
    val interaction = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Row(
        
        modifier = modifier
            .then(interaction)
            .defaultMinSize(minHeight = minHeight)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        label()
        Spacer(Modifier.width(DifftSettingsDefaults.LabelSpacing))
        DifftSwitch(checked = checked, onCheckedChange = null, enabled = switchAppearsEnabled)
    }
}
