package com.difft.android.base.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme


object DifftCheckBoxDefaults {
    
    val Size: Dp = 20.dp
    val CornerRadius: Dp = 2.5.dp
    val BorderWidth: Dp = 1.25.dp
    val BoxShape: Shape = RoundedCornerShape(CornerRadius)

    
    val TouchTarget: Dp = 32.dp

    
    val LabelGap: Dp = 8.dp
    val HelpGap: Dp = 4.dp
    val HelpIconSize: Dp = 14.dp
}




















@Composable
fun DifftCheckBox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    onHelpClick: (() -> Unit)? = null,
) {
    if (label == null) {
        Box(
            modifier = modifier.then(toggleModifier(checked, enabled, onCheckedChange, expandToTouchTarget = true)),
            contentAlignment = Alignment.Center,
        ) {
            CheckBoxGlyph(checked = checked, enabled = enabled)
        }
        return
    }
    Row(
        modifier = modifier.then(toggleModifier(checked, enabled, onCheckedChange, expandToTouchTarget = false)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBoxGlyph(checked = checked, enabled = enabled)
        Spacer(Modifier.width(DifftCheckBoxDefaults.LabelGap))
        Text(
            text = label,
            style = DifftTheme.typography.p3,
            color = DifftTheme.colors.textPrimary,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onHelpClick != null) {
            Spacer(Modifier.width(DifftCheckBoxDefaults.HelpGap))
            Icon(
                painter = painterResource(R.drawable.base_tabler_help),
                contentDescription = null,
                tint = DifftTheme.colors.icon,
                modifier = Modifier
                    .size(DifftCheckBoxDefaults.HelpIconSize)
                    .clickable(enabled = enabled, onClick = onHelpClick),
            )
        }
    }
}


@Composable
private fun CheckBoxGlyph(checked: Boolean, enabled: Boolean) {
    val fill = when {
        !enabled -> DifftTheme.colors.backgroundDisabled
        checked -> DifftTheme.colors.primary
        else -> Color.Transparent
    }
    val border = if (!checked && enabled) DifftTheme.colors.textDisabled else null
    val boxModifier = Modifier
        .size(DifftCheckBoxDefaults.Size)
        .clip(DifftCheckBoxDefaults.BoxShape)
        .background(fill)
    Box(
        modifier = if (border != null) {
            boxModifier.border(DifftCheckBoxDefaults.BorderWidth, border, DifftCheckBoxDefaults.BoxShape)
        } else {
            boxModifier
        },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.base_ic_checkbox_check),
                contentDescription = null,
                tint = if (enabled) DifftTheme.colors.onPrimary else DifftTheme.colors.textDisabled,
                modifier = Modifier.size(DifftCheckBoxDefaults.Size),
            )
        }
    }
}


@Composable
private fun toggleModifier(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    expandToTouchTarget: Boolean,
): Modifier {
    if (onCheckedChange == null) return Modifier
    val base = if (expandToTouchTarget) {
        Modifier.defaultMinSize(DifftTheme.spacing.minTouchTarget, DifftTheme.spacing.minTouchTarget)
    } else {
        Modifier
    }
    return base.toggleable(
        value = checked,
        enabled = enabled,
        role = Role.Checkbox,
        onValueChange = onCheckedChange,
    )
}
