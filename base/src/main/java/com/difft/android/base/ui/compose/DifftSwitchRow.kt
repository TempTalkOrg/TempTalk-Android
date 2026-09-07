package com.difft.android.base.ui.compose

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
import com.difft.android.base.ui.theme.DifftTheme

/**
 * Label + switch settings row: the shape the migrated XML `SwitchCompat` label rows and the Compose
 * settings pages all hand-rolled. The whole row is the touch target (matching `SwitchCompat`, which
 * is a TextView and therefore clickable across its full width); the inner [DifftSwitch] is visual
 * only.
 *
 * A null [onCheckedChange] makes the row inert — used by the
 * [com.difft.android.base.widget.DifftSwitchView] shell, which owns the click and the accessibility
 * node at the View level.
 *
 * [switchAppearsEnabled] decouples the switch's look from the row's interactivity. The one caller
 * that needs it is the proxy "protect IP in calls" row: greyed while the proxy is off, but still
 * tappable so the ViewModel can toast "enable the proxy first" instead of swallowing the tap.
 */
@Composable
fun DifftSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    switchAppearsEnabled: Boolean = enabled,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    minHeight: Dp = DifftSwitchDefaults.RowMinHeight,
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
        style = DifftSwitchDefaults.LabelTextStyle,
        color = DifftTheme.colors.textPrimary,
        modifier = Modifier.weight(1f),
    )
}

/** Slot form for rows whose label is composed (e.g. a trailing coloured value). */
@Composable
fun DifftSwitchRow(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    switchAppearsEnabled: Boolean = enabled,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    minHeight: Dp = DifftSwitchDefaults.RowMinHeight,
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
        // toggleable before padding so the padded area is tappable too, as on a `SwitchCompat`.
        modifier = modifier
            .then(interaction)
            .defaultMinSize(minHeight = minHeight)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        label()
        Spacer(Modifier.width(DifftSwitchDefaults.LabelSpacing))
        DifftSwitch(checked = checked, onCheckedChange = null, enabled = switchAppearsEnabled)
    }
}
