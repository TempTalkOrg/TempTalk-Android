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

/** Figma multi-select checkbox geometry. The View shell [com.difft.android.base.widget.DifftCheckBoxView] reuses it. */
object DifftCheckBoxDefaults {
    /** Library "Size 24 for Mobile": a 20dp box, radius box/8, unchecked border box/16. */
    val Size: Dp = 20.dp
    val CornerRadius: Dp = 2.5.dp
    val BorderWidth: Dp = 1.25.dp
    val BoxShape: Shape = RoundedCornerShape(CornerRadius)

    /** Wrap size of the AppCompat checkbox this replaces; keeps existing layouts from shifting. */
    val TouchTarget: Dp = 32.dp

    /** Labelled row: box, [LabelGap], label, [HelpGap], [HelpIconSize] help icon. */
    val LabelGap: Dp = 8.dp
    val HelpGap: Dp = 4.dp
    val HelpIconSize: Dp = 14.dp
}

/**
 * The app's multi-select checkbox at the library's mobile size: a 20dp box with 2.5dp corners, a
 * 1.25dp `textDisabled` border and no fill when unchecked, a `primary` fill with the library's
 * white check glyph when checked.
 *
 * Mirrors the Material3 `Checkbox` parameter shape so call sites migrate by renaming. A non-null
 * [onCheckedChange] makes the box toggleable in its own right with a 48dp touch target and
 * checkbox semantics; `null` renders the visual only, for rows that toggle on the row click and
 * carry the semantics themselves.
 *
 * Disabled states are solid colour swaps, never alpha: unchecked becomes a `backgroundDisabled`
 * fill with no border, checked keeps that fill and tints the glyph `textDisabled`. The library
 * pins its dark disabled-checked pair to the light hexes; both modes use the tokens here.
 *
 * A non-null [label] switches to the library's labelled row - box, 8dp, `p3` label, plus a 14dp
 * help icon 4dp after the label when [onHelpClick] is set - and moves the toggle to the whole
 * row. That row is content-height, with no 48dp minimum: it is already far wider than the touch
 * minimum, and forcing the height would overflow the fixed-height hosts that list such rows.
 */
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

/** The box itself; the glyph resource already places the check inside its 20dp viewport. */
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
                tint = if (enabled) DifftTheme.colors.iconOnPrimary else DifftTheme.colors.textDisabled,
                modifier = Modifier.size(DifftCheckBoxDefaults.Size),
            )
        }
    }
}

/** Builds a fresh chain, never the caller's, so `modifier.then(...)` cannot duplicate it. */
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
